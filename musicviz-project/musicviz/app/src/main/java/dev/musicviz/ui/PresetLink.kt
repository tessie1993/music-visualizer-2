package dev.musicviz.ui

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Presets as links, so one can be handed to someone else in any app that
 * carries text.
 *
 * A preset is already a self-contained JSON document, and the app has no
 * account, no server and no network permission - so "sharing" has to mean
 * putting the preset itself into the message rather than a pointer to it
 * somewhere. Gzip plus URL-safe Base64 turns ~4 KB of repetitive JSON into a
 * link short enough to paste into a chat, and the whole thing travels
 * offline: nothing is uploaded, and a link works forever because there is
 * nothing behind it to go away.
 *
 * A link that carries a custom GLSL shader is much longer, which is why
 * [MAX_LINK_LENGTH] exists - past it the caller offers the file instead.
 */
object PresetLink {
    /** Scheme + host the manifest registers, so a tap opens the app. */
    const val SCHEME = "musicviz"
    const val HOST = "preset"

    private const val PREFIX = "$SCHEME://$HOST/"

    /**
     * Longest link worth putting in a message.
     *
     * Chat apps break or truncate very long "URLs", and a link that arrives
     * mangled imports as nothing with no clue why. Past this the caller shares
     * the preset's `.json` file instead, which has no length limit and is what
     * a shader-carrying preset needs anyway.
     */
    const val MAX_LINK_LENGTH = 8_000

    /**
     * Most JSON a link is allowed to inflate to.
     *
     * A link arrives through an exported ACTION_VIEW intent, so it can be
     * hostile: gzip expands ~1000:1, and a few hundred KB of payload would
     * otherwise inflate to hundreds of MB and OOM-kill the app on import.
     * A real preset - custom shader and all - is well under 1 MB of JSON,
     * so 4 MB is generous headroom, not a functional limit.
     */
    private const val MAX_INFLATED_BYTES = 4 * 1024 * 1024

    /**
     * Longest Base64 payload worth decoding at all - a cheap first gate, so
     * an absurdly long intent string is rejected before it is even copied
     * into a byte array. Legitimate payloads are orders of magnitude smaller.
     */
    private const val MAX_PAYLOAD_LENGTH = 1024 * 1024

    /** True when [text] looks like one of our links (cheap pre-check). */
    fun isPresetLink(text: String): Boolean = text.trim().startsWith(PREFIX, ignoreCase = true)

    /** Wraps preset [json] into a shareable link. */
    fun encode(json: String): String {
        val deflated = ByteArrayOutputStream()
        GZIPOutputStream(deflated).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        val payload =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(deflated.toByteArray())
        return PREFIX + payload
    }

    /**
     * Preset JSON from a link, or null when [text] is not one / is corrupt.
     *
     * Never throws: a link arrives from a chat message, which means it arrives
     * truncated, re-wrapped or with a stray character on the end often enough
     * that failure is an ordinary outcome, not an exceptional one.
     */
    fun decode(text: String): String? {
        val trimmed = text.trim()
        if (!isPresetLink(trimmed)) return null
        val payload = trimmed.substring(PREFIX.length).substringBefore('#').substringBefore('?')
        if (payload.length > MAX_PAYLOAD_LENGTH) return null
        return runCatching {
            val bytes =
                java.util.Base64
                    .getUrlDecoder()
                    .decode(payload)
            GZIPInputStream(bytes.inputStream()).use { inflateBounded(it) }.toString(Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * Reads at most [MAX_INFLATED_BYTES] and throws past it, so the caller's
     * `runCatching` turns a gzip bomb into the same null a corrupt link gets.
     */
    private fun inflateBounded(gzip: GZIPInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = gzip.read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
            if (out.size() > MAX_INFLATED_BYTES) error("link inflates past $MAX_INFLATED_BYTES bytes")
        }
    }

    /**
     * The first link inside a longer piece of text.
     *
     * Pasting from a chat rarely yields the link alone - it comes with the
     * sender's name, a quote marker or a trailing newline - and asking the
     * user to trim it by hand for a paste button is asking them not to use it.
     */
    fun findIn(text: String): String? {
        val at = text.indexOf(PREFIX, ignoreCase = true)
        if (at < 0) return null
        val end = text.indexOfFirst(at) { it.isWhitespace() }
        return text.substring(at, end)
    }

    private inline fun String.indexOfFirst(
        from: Int,
        predicate: (Char) -> Boolean,
    ): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return length
    }
}
