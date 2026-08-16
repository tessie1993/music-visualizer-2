package dev.musicviz.ui

import android.app.Application
import dev.musicviz.audio.CaptureFailure
import dev.musicviz.audio.MicCapture
import dev.musicviz.audio.NowPlayingBridge
import dev.musicviz.audio.PlaybackCapture
import dev.musicviz.audio.PlaybackCaptureService
import dev.musicviz.audio.playbackCaptureSupported
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Live-input state for the Settings switch: running, plus why it is not. */
data class MicState(
    val active: Boolean = false,
    val failure: MicCapture.Failure? = null,
)

/**
 * "Visualize other apps": whether the capture is running, what it can hear,
 * and - when it can hear nothing - enough context to say why.
 */
data class ExternalAudioState(
    /** False on Android 9 and older, where the API does not exist. */
    val supported: Boolean = playbackCaptureSupported,
    /** True while the capture is open and feeding the analyzer. */
    val active: Boolean = false,
    /** True while waiting for the user to answer the system consent dialog. */
    val awaitingConsent: Boolean = false,
    val failure: CaptureFailure? = null,
    /** What another app's media session says is playing, when readable. */
    val nowPlaying: NowPlayingBridge.External? = null,
    /** True when the notification-listener switch is on, so [nowPlaying] works. */
    val hasSessionAccess: Boolean = false,
    /**
     * The capture is open, something is playing, and every sample has been an
     * exact zero for seconds: the playing app forbids capture. Spotify is the
     * one people hit.
     */
    val refusedByApp: Boolean = false,
) {
    /** The app to name in a "…won't let us listen" message, if we know it. */
    val refusingApp: String? get() = if (refusedByApp) nowPlaying?.appLabel else null
}

/**
 * The two alternate audio sources - the microphone and other apps' playback -
 * extracted from [PlayerViewModel]. Both write into the one ring buffer the
 * playback tap feeds, so ownership of the analyzer (its reset and its sample
 * rate) is the coupling this controller manages; what it needs from the rest
 * of the app is the four-line [Host].
 */
internal class CaptureController(
    private val application: Application,
    scope: CoroutineScope,
    capture: dev.musicviz.engine.audio.PcmSink,
    private val host: Host,
) {
    /** What a source switch touches outside the captures themselves. */
    interface Host {
        /** One ring, one source: our own playback steps aside for a capture. */
        fun pausePlayback()

        /** Drops the per-track analysis state; a new source is a new "track". */
        fun resetAnalysis()

        /** Retunes the analyzer to the rate now feeding the ring. */
        fun setAnalysisRate(rateHz: Int)

        /** Mirrors the mic switch into the persisted GUI prefs. */
        fun setMicReactivePref(on: Boolean)
    }

    /**
     * "Live input": the microphone as a second producer for the SAME ring
     * buffer the playback tap writes into, so every consumer downstream is
     * unchanged. Nothing is stored or transmitted - see [MicCapture].
     */
    private val micCapture = MicCapture(application, capture)

    private val _micState = MutableStateFlow(MicState())

    /** Microphone-driven visuals: on/off plus the last failure to report. */
    val micState: StateFlow<MicState> = _micState

    /**
     * Third producer for the one ring buffer, after the playback tap and the
     * microphone. Held on every API level; only starting it needs Android 10,
     * and that gate lives in [startPlaybackCapture] where the reason for it
     * can be turned into something the user reads.
     */
    private val playbackCapture = PlaybackCapture(capture)

    private val nowPlayingBridge = NowPlayingBridge(application)

    private val _externalAudio = MutableStateFlow(ExternalAudioState())

    /** State behind the "Visualize other apps" card. */
    val externalAudio: StateFlow<ExternalAudioState> = _externalAudio

    /** True while the microphone is actually feeding the ring. */
    val micActive: Boolean get() = micCapture.active

    /**
     * Sample rate the decoded audio pipeline last reconfigured to, remembered
     * so live input can hand the analyzer back the playback rate when it
     * stops. Written from the playback thread.
     */
    @Volatile
    private var tapSampleRateHz: Int = 0

    /**
     * Installed on the playback session by the ViewModel (held as a field so
     * teardown can identity-check that the hook on the player is still this
     * screen's own before clearing it).
     */
    val audioFormatHook: (sampleRateHz: Int, channelCount: Int, encoding: Int) -> Unit =
        { rate, _, _ ->
            // Live input owns the analyzer's rate while it is running: the
            // player can still reconfigure its pipeline (a queued track being
            // prepared) and would otherwise retune the FFT to a rate no
            // samples are arriving at.
            tapSampleRateHz = rate
            if (!externalAudioOwnsAnalyzer()) host.setAnalysisRate(rate)
        }

    init {
        // Consent -> foreground service -> projection -> recorder. This is the
        // last hop: the service publishes what the user granted, and the
        // recorder opens against it here, where the ring buffer lives.
        scope.launch {
            dev.musicviz.audio.MediaProjectionHolder.projection
                .collect { projection ->
                    if (projection != null) {
                        startPlaybackCapture(projection)
                    } else if (_externalAudio.value.active) {
                        // Revoked from the system UI, or the service died.
                        playbackCapture.stop()
                        host.resetAnalysis()
                        host.setAnalysisRate(playbackRateFallback())
                        _externalAudio.update { it.copy(active = false, refusedByApp = false) }
                    }
                }
        }
        // The other half of that hop: a start that produced NO projection.
        // `projection` cannot carry it - it already holds null on a failed
        // first start, and a StateFlow does not re-emit a value it is already
        // at - so the service ticks a separate counter, and only its CHANGES
        // mean anything, hence drop(1). With nothing collecting it the switch
        // stayed on and "Waiting for the capture permission…" stayed up for
        // the rest of the session whenever getMediaProjection refused the
        // consent it was handed (an expired token, an OEM that says no).
        scope.launch {
            dev.musicviz.audio.MediaProjectionHolder.startFailures
                .drop(1)
                .collect {
                    _externalAudio.update {
                        it.copy(awaitingConsent = false, failure = CaptureFailure.CONSENT)
                    }
                }
        }
    }

    /** The playback rate to hand the analyzer back when a capture stops. */
    private fun playbackRateFallback(): Int = tapSampleRateHz.takeIf { it > 0 } ?: 44100

    /**
     * True while a source other than our own playback is feeding the ring
     * buffer, and therefore owns the analyzer's sample rate.
     */
    private fun externalAudioOwnsAnalyzer(): Boolean = micCapture.active || playbackCapture.active

    /**
     * Turns live input on or off.
     *
     * Turning it ON pauses playback: the ring buffer has ONE analysis window,
     * so a track and the room would be summed into a single spectrum and
     * neither would drive the visuals recognisably. That is also what the
     * feature asks for - visuals reacting to the room, with no music playing.
     *
     * Returns the failure that stopped it, or null on success. A refused
     * permission is reported rather than swallowed so the caller can send the
     * user to the system prompt instead of leaving a switch that silently
     * springs back.
     */
    fun setMicEnabled(enabled: Boolean): MicCapture.Failure? {
        if (!enabled) {
            micCapture.stop()
            host.resetAnalysis()
            host.setAnalysisRate(playbackRateFallback())
            _micState.value = MicState(active = false)
            host.setMicReactivePref(false)
            return null
        }
        if (micCapture.active) return null
        host.pausePlayback()
        val failure = micCapture.start { rate -> host.setAnalysisRate(rate) }
        if (failure != null) {
            _micState.value = MicState(active = false, failure = failure)
            return failure
        }
        // The beat grid and energy envelope model one continuous piece of
        // audio; the room is a different one, exactly like a track change.
        host.resetAnalysis()
        _micState.value = MicState(active = true)
        host.setMicReactivePref(true)
        return null
    }

    /** True when the RECORD_AUDIO permission is already granted. */
    fun hasMicPermission(): Boolean = micCapture.hasPermission()

    /**
     * Records that the consent dialog is up, so the switch can show that it is
     * waiting rather than springing back while the system UI is in front.
     */
    fun noteExternalAudioConsentPending() {
        _externalAudio.update { it.copy(awaitingConsent = true, failure = null) }
    }

    /** The user dismissed the system capture dialog. */
    fun noteExternalAudioConsentDenied() {
        _externalAudio.update {
            it.copy(awaitingConsent = false, failure = CaptureFailure.CONSENT)
        }
    }

    /**
     * Starts reading another app's audio with a projection the service has
     * just published. Called from the [dev.musicviz.audio.MediaProjectionHolder]
     * collector, not by the UI: consent, the foreground service and the
     * recorder are three separate steps and only the last one belongs here.
     */
    private fun startPlaybackCapture(projection: android.media.projection.MediaProjection) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            _externalAudio.update {
                it.copy(awaitingConsent = false, failure = CaptureFailure.UNSUPPORTED)
            }
            return
        }
        if (!micCapture.hasPermission()) {
            _externalAudio.update {
                it.copy(awaitingConsent = false, failure = CaptureFailure.PERMISSION)
            }
            return
        }
        // One ring buffer, one source. Our own playback and the microphone
        // both step aside, exactly as they do for each other.
        host.pausePlayback()
        if (micCapture.active) setMicEnabled(false)
        val failure = playbackCapture.start(projection) { rate -> host.setAnalysisRate(rate) }
        // The beat grid and energy envelope model one continuous piece of
        // audio; another app's stream is a different one, like a track change.
        host.resetAnalysis()
        if (failure != null) {
            // The service is what the consent flow started, and it is running
            // by the time we get here. A recorder that never opened leaves it -
            // and its "this app can hear you" notification - standing over a
            // capture that does not exist, with the only way back a switch the
            // user just watched fail. Hand the analyzer back too, since nothing
            // is going to feed it.
            host.setAnalysisRate(playbackRateFallback())
            PlaybackCaptureService.stop(application)
        }
        _externalAudio.update {
            it.copy(
                active = failure == null,
                awaitingConsent = false,
                failure = failure,
                refusedByApp = false,
            )
        }
    }

    /** Stops the capture and takes the foreground service down with it. */
    fun stopExternalAudio() {
        playbackCapture.stop()
        host.resetAnalysis()
        host.setAnalysisRate(playbackRateFallback())
        PlaybackCaptureService.stop(application)
        _externalAudio.update {
            it.copy(active = false, awaitingConsent = false, refusedByApp = false)
        }
    }

    /** Where to send the user to switch the notification listener on. */
    fun notificationAccessIntent(): android.content.Intent = nowPlayingBridge.settingsIntent()

    /**
     * Refreshes what another app is playing, and re-decides whether a silent
     * capture is being refused.
     *
     * The refusal verdict needs BOTH halves: the capture reporting nothing but
     * exact zeroes, and a session reporting that something is in fact playing.
     * Either alone is ordinary - a paused phone is silent, and a session can
     * be playing while the capture is simply not running.
     */
    fun refreshExternalAudio() {
        val state = _externalAudio.value
        val access = nowPlayingBridge.hasAccess()
        val now = if (access) nowPlayingBridge.current() else null
        val refused = playbackCapture.active && playbackCapture.blockedLikely && (now?.playing ?: false)
        val next =
            state.copy(
                active = playbackCapture.active,
                nowPlaying = now,
                hasSessionAccess = access,
                refusedByApp = refused,
            )
        if (next != state) _externalAudio.value = next
    }

    /**
     * Notices a microphone that died under us and puts the switch back.
     *
     * [MicCapture.active] goes false on its own when the recorder stops mid-
     * capture - a call takes the microphone, another app grabs it, the device
     * refuses a read - but nothing else re-reads it: [_micState] is otherwise
     * only ever written by [setMicEnabled]. So the switch stayed on, the
     * "listening" affordance stayed up, and the visuals sat on a spectrum that
     * had stopped arriving. Hands the analyzer back to playback the same way
     * an explicit switch-off does.
     */
    fun refreshMicState() {
        if (!_micState.value.active || micCapture.active) return
        host.resetAnalysis()
        host.setAnalysisRate(playbackRateFallback())
        _micState.value = MicState(active = false, failure = MicCapture.Failure.UNAVAILABLE)
        host.setMicReactivePref(false)
    }

    /** Teardown: both captures released; the capture service comes down with its recorder. */
    fun shutdown() {
        // The microphone goes first: an open AudioRecord outliving the screen
        // would keep the recording indicator up with nothing left to read it.
        micCapture.stop()
        // Same for the playback capture, which additionally holds a
        // foreground service and its "this app can hear you" notification.
        if (_externalAudio.value.active) stopExternalAudio()
    }
}
