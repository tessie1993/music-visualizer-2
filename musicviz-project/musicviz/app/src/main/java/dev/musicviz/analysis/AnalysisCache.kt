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
 *   header: magic "MVAC", version, hopMs, key(UTF), frameCount,
 *           bandCount, waveformSize
 *   frame:  timeMs(Long), bands as Short*bandCount (x8192, clamped),
 *           waveform as Short*waveformSize (x32767), rms/bass/mid/treble/
 *           onset/bpm/centroid as Float, beat as Byte
 *
 * Eviction is LRU by file mtime, capped at [MAX_ENTRIES]. All methods are
 * blocking; call on Dispatchers.IO.
 */
object AnalysisCache {
    private const val MAGIC = 0x4D564143 // "MVAC"
    private const val VERSION = 1
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

    fun load(
        context: Context,
        uri: Uri,
    ): FeatureTimeline? {
        val f = fileFor(context, uri)
        if (!f.exists()) return null
        return runCatching {
            DataInputStream(f.inputStream().buffered()).use { d ->
                if (d.readInt() != MAGIC || d.readInt() != VERSION) return@runCatching null
                val hopMs = d.readLong()
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
                    frames +=
                        TimelineFrame(
                            timeMs,
                            AudioFeatures(bands, wave, rms, bass, mid, treble, onset, beat, bpm, centroid),
                        )
                }
                f.setLastModified(System.currentTimeMillis()) // LRU touch
                FeatureTimeline(frames, hopMs, key)
            }
        }.getOrNull()
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
                d.writeUTF(t.key)
                d.writeInt(t.frames.size)
                val bandCount = t.frames[0].features.bands.size
                val waveSize = t.frames[0].features.waveform.size
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
