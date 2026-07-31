package dev.musicviz.analysis

import android.content.Context
import android.net.Uri
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Persistent per-track analysis cache: serialized [FeatureTimeline]s in
 * files/analysis/, keyed by a hash of the source URI. A cache hit lets
 * playback intelligence and (especially) video export skip the whole
 * offline-analysis phase. Binary layout, little JVM-default big-endian via
 * DataStreams:
 *
 *   header: magic "MVAC", version, hopMs, hopRateHz, key(UTF), frameCount,
 *           bandCount, waveformSize
 *   frame:  timeMs(Long), bands as Short*bandCount (x8192, clamped),
 *           waveform as Short*waveformSize (x32767), rms/bass/mid/treble/
 *           onset/bpm/centroid as Float, beat as Byte, flux as Float
 *
 * The key is deliberately the URI alone, with no beat sensitivity folded in:
 * v2 stores the raw onset curve (`flux`) and [load] re-decides the beats at
 * the caller's current sensitivity, so a single entry stays valid for every
 * setting. Keying on the settings instead would re-analyse the whole track on
 * every slider drag and thrash the 15-entry LRU.
 *
 * v1 stored only the decided beat flags and no flux, so its entries cannot be
 * re-thresholded; [load] deletes them on sight and the track is re-analysed
 * once.
 *
 * Eviction is LRU by file mtime, capped at [MAX_ENTRIES]. All methods are
 * blocking; call on Dispatchers.IO.
 */
object AnalysisCache {
    private const val MAGIC = 0x4D564143 // "MVAC"

    /** v1: decided beats only. v2: + hopRateHz header and a per-frame flux. */
    private const val VERSION = 2
    private const val MAX_ENTRIES = 15

    private fun dir(context: Context): File = File(context.filesDir, "analysis").apply { mkdirs() }

    private fun fileFor(
        context: Context,
        uri: Uri,
    ): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(uri.toString().toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(dir(context), "$name.mvac")
    }

    /**
     * Reads the cached timeline and decides its beats at the given
     * sensitivity - the same values the live [AnalysisEngine] is running, so
     * an export of a cached track matches what playback showed. Returns null
     * (and drops the file) for a corrupt or pre-v2 entry.
     */
    fun load(
        context: Context,
        uri: Uri,
        beatThresholdSigma: Float,
        beatMinIntervalMs: Float,
    ): FeatureTimeline? {
        val f = fileFor(context, uri)
        if (!f.exists()) return null
        val loaded =
            runCatching {
                DataInputStream(f.inputStream().buffered()).use { d ->
                    if (d.readInt() != MAGIC || d.readInt() != VERSION) return@runCatching null
                    val hopMs = d.readLong()
                    val hopRateHz = d.readFloat()
                    val key = d.readUTF()
                    val frameCount = d.readInt()
                    val bandCount = d.readInt()
                    val waveSize = d.readInt()
                    if (frameCount < 0 || frameCount > 1_000_000) return@runCatching null
                    val frames = ArrayList<TimelineFrame>(frameCount)
                    repeat(frameCount) {
                        val timeMs = d.readLong()
                        val bands = FloatArray(bandCount) { d.readShort() / 8192f }
                        val wave = FloatArray(waveSize) { d.readShort() / 32767f }
                        val rms = d.readFloat()
                        val bass = d.readFloat()
                        val mid = d.readFloat()
                        val treble = d.readFloat()
                        val onset = d.readFloat()
                        val bpm = d.readFloat()
                        val centroid = d.readFloat()
                        val beat = d.readByte().toInt() != 0
                        val flux = d.readFloat()
                        frames +=
                            TimelineFrame(
                                timeMs,
                                AudioFeatures(
                                    bands = bands,
                                    waveform = wave,
                                    rms = rms,
                                    bass = bass,
                                    mid = mid,
                                    treble = treble,
                                    onset = onset,
                                    beat = beat,
                                    bpm = bpm,
                                    centroid = centroid,
                                    flux = flux,
                                ),
                            )
                    }
                    f.setLastModified(System.currentTimeMillis()) // LRU touch
                    FeatureTimeline(frames, hopMs, key, hopRateHz)
                }
            }.getOrNull()
        if (loaded == null) {
            // Stale format or damaged file: drop it instead of leaving it to
            // fail every load until the LRU happens to evict it.
            runCatching { f.delete() }
            return null
        }
        return loaded.withBeatSensitivity(beatThresholdSigma, beatMinIntervalMs)
    }

    fun save(
        context: Context,
        uri: Uri,
        t: FeatureTimeline,
    ) {
        if (t.frames.isEmpty()) return
        val f = fileFor(context, uri)
        runCatching {
            DataOutputStream(f.outputStream().buffered()).use { d ->
                d.writeInt(MAGIC)
                d.writeInt(VERSION)
                d.writeLong(t.hopMs)
                d.writeFloat(t.hopRateHz)
                d.writeUTF(t.key)
                d.writeInt(t.frames.size)
                val bandCount =
                    t.frames[0]
                        .features.bands.size
                val waveSize =
                    t.frames[0]
                        .features.waveform.size
                d.writeInt(bandCount)
                d.writeInt(waveSize)
                for (fr in t.frames) {
                    d.writeLong(fr.timeMs)
                    val fe = fr.features
                    for (i in 0 until bandCount) {
                        d.writeShort(((fe.bands.getOrElse(i) { 0f }) * 8192f).toInt().coerceIn(-32768, 32767))
                    }
                    for (i in 0 until waveSize) {
                        d.writeShort(((fe.waveform.getOrElse(i) { 0f }) * 32767f).toInt().coerceIn(-32768, 32767))
                    }
                    d.writeFloat(fe.rms)
                    d.writeFloat(fe.bass)
                    d.writeFloat(fe.mid)
                    d.writeFloat(fe.treble)
                    d.writeFloat(fe.onset)
                    d.writeFloat(fe.bpm)
                    d.writeFloat(fe.centroid)
                    d.writeByte(if (fe.beat) 1 else 0)
                    // Full precision on purpose: the gate compares flux with a
                    // mean + sigma * std of its own history, so quantising the
                    // curve would move beats around on reload.
                    d.writeFloat(fe.flux)
                }
            }
            evict(context)
        }.onFailure { runCatching { f.delete() } }
    }

    private fun evict(context: Context) {
        val files = dir(context).listFiles()?.sortedBy { it.lastModified() } ?: return
        var excess = files.size - MAX_ENTRIES
        for (f in files) {
            if (excess <= 0) break
            if (f.delete()) excess--
        }
    }

    fun sizeBytes(context: Context): Long = dir(context).listFiles()?.sumOf { it.length() } ?: 0L

    fun entryCount(context: Context): Int = dir(context).listFiles()?.size ?: 0

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }
}
