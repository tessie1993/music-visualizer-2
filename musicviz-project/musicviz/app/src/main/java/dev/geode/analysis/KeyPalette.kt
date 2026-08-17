package dev.geode.analysis

/**
 * The track's musical key as a colour.
 *
 * [KeyDetector] already estimates a key for every analysed track and the
 * library shows it as text. Turning it into a hue gives a track a colour
 * IDENTITY it keeps across styles and sessions - the same song comes up the
 * same colour every time, and two songs that sound related look related.
 *
 * The mapping is the CIRCLE OF FIFTHS, not the chromatic scale. Keys a fifth
 * apart share six of seven notes and mix without clashing; keys a semitone
 * apart share almost nothing. Walking the hue wheel in fifths therefore puts
 * musically-near keys near each other in colour - so a set that moves C -> G
 * -> D drifts smoothly through the spectrum, where a chromatic mapping would
 * have thrown it right across the wheel on every change. It is also what the
 * Camelot wheel every DJ tool prints on its key readout does, for the same
 * reason.
 *
 * Minor keys sit a small step around from their relative major rather than on
 * top of it: related enough to read as the same family, distinct enough that
 * A minor and C major are not the same colour.
 */
object KeyPalette {
    /** Pitch classes in fifths order, so index = position on the wheel. */
    private val CIRCLE_OF_FIFTHS =
        listOf("C", "G", "D", "A", "E", "B", "F#", "C#", "G#", "D#", "A#", "F")

    /** Chromatic order, for transposing a minor root to its relative major. */
    private val CHROMATIC =
        listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** Semitones from a minor root up to its relative major (A minor -> C). */
    private const val RELATIVE_MAJOR_SEMITONES = 3

    /** Enharmonic spellings the detector never emits but a user might type. */
    private val ENHARMONIC =
        mapOf(
            "DB" to "C#",
            "EB" to "D#",
            "GB" to "F#",
            "AB" to "G#",
            "BB" to "A#",
            "CB" to "B",
            "FB" to "E",
            "E#" to "F",
            "B#" to "C",
        )

    /**
     * How far a minor key sits from its relative major, in hue.
     *
     * Small on purpose: A minor and C major are the same seven notes, so they
     * should read as the same colour family. Big enough that the two are
     * distinguishable side by side - about a fifth of the gap between
     * neighbouring keys on the wheel.
     */
    private const val MINOR_OFFSET = 1f / 12f * 0.2f

    /**
     * Hue in [0,1) for a [KeyDetector.finish] string ("A minor", "F# major"),
     * or null when the key is unknown or unparseable.
     *
     * Null rather than a default hue: "no key was detected" and "the key is C"
     * are different facts, and colouring an unanalysed track as if it were in
     * C would be inventing information the analysis never produced.
     */
    fun hueFor(key: String): Float? {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return null
        val minor = trimmed.contains("minor", ignoreCase = true)
        val spelled = trimmed.substringBefore(' ').uppercase().let { ENHARMONIC[it] ?: it }
        // A minor key is placed at its RELATIVE MAJOR's position, not its own
        // root's. A minor and C major are the same seven notes, so they are
        // interchangeable in a mix and have to be neighbours in colour; using
        // the minor's own root would put A minor three steps from C major,
        // as far away as keys that genuinely clash. This is the same placement
        // the Camelot wheel on every DJ tool uses, for the same reason.
        val root =
            if (minor) {
                val at = CHROMATIC.indexOf(spelled)
                if (at < 0) return null
                CHROMATIC[(at + RELATIVE_MAJOR_SEMITONES) % CHROMATIC.size]
            } else {
                spelled
            }
        val at = CIRCLE_OF_FIFTHS.indexOf(root)
        if (at < 0) return null
        val hue = at.toFloat() / CIRCLE_OF_FIFTHS.size + if (minor) MINOR_OFFSET else 0f
        return hue - kotlin.math.floor(hue)
    }

    /**
     * True when [a] and [b] are adjacent on the circle of fifths - the
     * relationship the hue distance is supposed to preserve. Exists so the
     * gate can state the property rather than pinning individual numbers.
     */
    fun areNeighbours(
        a: String,
        b: String,
    ): Boolean {
        val ai = CIRCLE_OF_FIFTHS.indexOf(a.trim().substringBefore(' ').uppercase())
        val bi = CIRCLE_OF_FIFTHS.indexOf(b.trim().substringBefore(' ').uppercase())
        if (ai < 0 || bi < 0) return false
        val gap = kotlin.math.abs(ai - bi)
        return gap == 1 || gap == CIRCLE_OF_FIFTHS.size - 1
    }

    /** Shortest distance between two hues on the wheel (both in [0,1)). */
    fun hueDistance(
        a: Float,
        b: Float,
    ): Float {
        val d = kotlin.math.abs(a - b)
        return minOf(d, 1f - d)
    }
}
