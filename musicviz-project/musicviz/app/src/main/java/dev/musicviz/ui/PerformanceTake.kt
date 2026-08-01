package dev.musicviz.ui

import dev.musicviz.render.scene.SceneParams
import org.json.JSONArray
import org.json.JSONObject

/**
 * A recorded VJ performance: what the visuals were doing, moment by moment,
 * rather than the pixels that came out.
 *
 * The point of storing the performance instead of a render is that it can be
 * replayed at any quality later, and edited afterwards - a 4K export of a set
 * you improvised on a phone costs a re-render, not a re-performance. It is
 * also tiny: a five-minute take is a few tens of kilobytes.
 *
 * FORMAT. A take is one JSON document holding a list of keyframes:
 *
 *     { "name": …, "trackUri": …, "durationMs": …, "events": [
 *         { "t": 0,    "s": "fluid", "p": { …every parameter… } },
 *         { "t": 1160, "p": { "speed": 1.4 } },
 *         { "t": 4300, "s": "julia", "m": "/…/x.milk" } ] }
 *
 * The first keyframe carries the full parameter set; every later one carries
 * only what CHANGED (`p`), plus the style (`s`) and MilkDrop preset (`m`) when
 * those changed. That delta is what keeps a take small enough to record
 * continuously: dragging one slider writes one number per keyframe instead of
 * a hundred and thirty, which is the difference between kilobytes and
 * megabytes over a track.
 *
 * Parameters go through [PresetStore.paramsToJson], so a take covers every
 * field a preset does - including ones added later - with no second list.
 */
object PerformanceTake {
    /**
     * Shortest gap between keyframes while recording.
     *
     * A slider drag emits a state change per frame; at 60 Hz that is 60
     * keyframes a second for a gesture the eye reads as one smooth sweep. 80
     * ms is under the ~100 ms at which a parameter change stops looking
     * instantaneous, so nothing observable is lost, and it bounds a long set
     * to something a phone can hold and re-read.
     */
    const val MIN_KEYFRAME_GAP_MS = 80L

    /**
     * Keyframe ceiling. At [MIN_KEYFRAME_GAP_MS] this is about an hour of
     * continuous knob-twiddling; past it recording stops rather than growing
     * without bound, because a take nobody can load is worse than a short one.
     */
    const val MAX_EVENTS = 45_000

    /** The visual state a take asks for at one instant. */
    data class State(
        val sceneId: String,
        val params: SceneParams,
        val milkPath: String?,
    )

    /**
     * Records keyframes as the live visual state changes.
     *
     * Fed from the ViewModel's own state flow rather than from each control,
     * so anything that moves the visuals is captured by construction: sliders,
     * preset applies, Randomize, style switches, the scene-intelligence
     * auto-switcher. A control added later is recorded without being told to.
     */
    class Recorder(
        sceneId: String,
        params: SceneParams,
        milkPath: String?,
        /** Overridable so the cap can be exercised without recording an hour. */
        private val maxEvents: Int = MAX_EVENTS,
    ) {
        private val events = JSONArray()
        private var accum = PresetStore.paramsToJson(params)
        private var lastSceneId = sceneId
        private var lastMilkPath = milkPath
        private var lastAtMs = Long.MIN_VALUE

        /** Keyframes recorded so far, for the "recording…" readout. */
        val size: Int get() = events.length()

        /** False once [MAX_EVENTS] is reached; the recorder stops appending. */
        val hasRoom: Boolean get() = events.length() < maxEvents

        init {
            // The opening keyframe is absolute: a take must be replayable from
            // nothing, not only from whatever the app happened to be showing.
            events.put(
                JSONObject()
                    .put("t", 0L)
                    .put("s", sceneId)
                    .put("p", JSONObject(accum.toString()))
                    .apply { if (milkPath != null) put("m", milkPath) },
            )
            lastAtMs = 0L
        }

        /**
         * Appends the state at [atMs] if anything changed and the throttle
         * allows it. Returns true when a keyframe was written.
         */
        fun append(
            atMs: Long,
            sceneId: String,
            params: SceneParams,
            milkPath: String?,
        ): Boolean {
            if (!hasRoom) return false
            if (atMs - lastAtMs < MIN_KEYFRAME_GAP_MS) return false
            val next = PresetStore.paramsToJson(params)
            val delta = diff(accum, next)
            val sceneChanged = sceneId != lastSceneId
            val milkChanged = milkPath != lastMilkPath
            if (delta.length() == 0 && !sceneChanged && !milkChanged) return false
            val event = JSONObject().put("t", atMs)
            if (sceneChanged) event.put("s", sceneId)
            if (milkChanged && milkPath != null) event.put("m", milkPath)
            if (delta.length() > 0) event.put("p", delta)
            events.put(event)
            accum = next
            lastSceneId = sceneId
            lastMilkPath = milkPath
            lastAtMs = atMs
            return true
        }

        /** Serializes the take. [durationMs] is how long the recording ran. */
        fun finish(
            name: String,
            trackUri: String?,
            durationMs: Long,
        ): String =
            JSONObject()
                .put("name", name)
                .put("durationMs", durationMs)
                .apply { if (trackUri != null) put("trackUri", trackUri) }
                .put("events", events)
                .toString()
    }

    /**
     * Reads a take back: [stateAt] resolves the accumulated state at any
     * instant.
     *
     * Playback is sequential, so the cursor walks forward and only rewinds
     * when the caller seeks backwards - which is what makes scrubbing a take
     * cheap without holding a fully-expanded copy of every keyframe in memory.
     */
    class Timeline(
        json: String,
    ) {
        private val root = JSONObject(json)
        private val events: JSONArray = root.optJSONArray("events") ?: JSONArray()

        val name: String = root.optString("name", "Take")
        val trackUri: String? = root.optString("trackUri", "").takeIf { it.isNotEmpty() }
        val durationMs: Long = root.optLong("durationMs", 0L)
        val eventCount: Int get() = events.length()

        /** True when the take carries nothing to replay. */
        val isEmpty: Boolean get() = events.length() == 0

        private var cursor = 0
        private var accum = JSONObject()
        private var sceneId = ""
        private var milkPath: String? = null
        private var appliedThroughMs = Long.MIN_VALUE

        /**
         * The take's state at [ms], or null when it holds no keyframes.
         *
         * Every keyframe at or before [ms] is folded in, so a seek lands on
         * the same state continuous playback would have reached - a take is a
         * description of the whole span, not a list of one-shot triggers.
         */
        fun stateAt(ms: Long): State? {
            if (events.length() == 0) return null
            if (ms < appliedThroughMs) rewind()
            while (cursor < events.length()) {
                val e = events.optJSONObject(cursor) ?: break
                if (e.optLong("t", 0L) > ms) break
                apply(e)
                cursor++
            }
            appliedThroughMs = ms
            if (cursor == 0) return null
            return State(sceneId, PresetStore.paramsFromJson(accum), milkPath)
        }

        /** Timestamp of the last keyframe; the take's real length. */
        fun lastEventMs(): Long = events.optJSONObject(events.length() - 1)?.optLong("t", 0L) ?: 0L

        private fun rewind() {
            cursor = 0
            accum = JSONObject()
            sceneId = ""
            milkPath = null
            appliedThroughMs = Long.MIN_VALUE
        }

        private fun apply(e: JSONObject) {
            e.optString("s", "").takeIf { it.isNotEmpty() }?.let { sceneId = it }
            e.optString("m", "").takeIf { it.isNotEmpty() }?.let { milkPath = it }
            val p = e.optJSONObject("p") ?: return
            for (key in p.keys()) accum.put(key, p.get(key))
        }
    }

    /**
     * Keys whose values differ between [from] and [to], as a fresh object.
     *
     * Compared through `opt`/`equals` on the boxed values rather than
     * numerically: both sides come from the same serializer, so a value that
     * did not change is byte-identical, and a value that did is worth a
     * keyframe however small the change.
     */
    private fun diff(
        from: JSONObject,
        to: JSONObject,
    ): JSONObject {
        val out = JSONObject()
        for (key in to.keys()) {
            val next = to.get(key)
            if (from.opt(key) != next) out.put(key, next)
        }
        return out
    }
}
