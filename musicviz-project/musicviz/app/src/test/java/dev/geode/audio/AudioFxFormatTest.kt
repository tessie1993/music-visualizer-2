package dev.geode.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioFxFormatTest {
    @Test
    fun `sub-kilohertz frequencies label in Hz`() {
        assertEquals("60 Hz", AudioFxFormat.freqLabel(60_000))
        assertEquals("230 Hz", AudioFxFormat.freqLabel(230_000))
        assertEquals("910 Hz", AudioFxFormat.freqLabel(910_000))
        assertEquals("0 Hz", AudioFxFormat.freqLabel(0))
    }

    @Test
    fun `kilohertz frequencies label in kHz with one decimal only when needed`() {
        assertEquals("1 kHz", AudioFxFormat.freqLabel(1_000_000))
        assertEquals("3.6 kHz", AudioFxFormat.freqLabel(3_600_000))
        assertEquals("14 kHz", AudioFxFormat.freqLabel(14_000_000))
        // Sub-tenth precision is truncated, never rounded up into a lie.
        assertEquals("14.2 kHz", AudioFxFormat.freqLabel(14_250_000))
    }

    @Test
    fun `millibel gains label as signed dB`() {
        assertEquals("+15 dB", AudioFxFormat.dbLabel(1_500))
        assertEquals("-15 dB", AudioFxFormat.dbLabel(-1_500))
        assertEquals("0 dB", AudioFxFormat.dbLabel(0))
        assertEquals("+3.5 dB", AudioFxFormat.dbLabel(350))
        assertEquals("-0.5 dB", AudioFxFormat.dbLabel(-50))
    }

    @Test
    fun `band level CSV roundtrips`() {
        val levels = listOf(-1500, -300, 0, 150, 1500)
        val csv = AudioFxFormat.encodeBandLevels(levels)
        assertEquals("-1500,-300,0,150,1500", csv)
        assertEquals(levels, AudioFxFormat.decodeBandLevels(csv))
    }

    @Test
    fun `decode tolerates garbage`() {
        assertEquals(emptyList<Int>(), AudioFxFormat.decodeBandLevels(null))
        assertEquals(emptyList<Int>(), AudioFxFormat.decodeBandLevels(""))
        assertEquals(emptyList<Int>(), AudioFxFormat.decodeBandLevels("not,numbers"))
        // Malformed entries are skipped, valid ones kept in order.
        assertEquals(listOf(100, 300), AudioFxFormat.decodeBandLevels("100, x ,300"))
        assertEquals(listOf(-200, 500), AudioFxFormat.decodeBandLevels(" -200 , 500 "))
    }

    @Test
    fun `empty band list encodes to empty string`() {
        assertEquals("", AudioFxFormat.encodeBandLevels(emptyList()))
    }
}
