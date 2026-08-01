package dev.musicviz.render

import android.content.Context
import android.opengl.GLES30
import org.json.JSONArray
import org.json.JSONObject

/**
 * The transition library: the five built-in styles plus the vendored
 * gl-transitions corpus.
 *
 * ### Why a corpus rather than more hand-written styles
 *
 * A scene switch is the most-viewed animation in the app - random mode, the
 * visual playlist, the intelligence suggester and every manual pick route
 * through it - and it had five looks, one of which is a cut. gl-transitions is
 * 123 more, MIT/BSD licensed, and already written against the exact contract
 * `composite_frag.glsl` implements: `vec4 transition(vec2 uv)` reading
 * `progress`, `ratio`, `getFromColor()` and `getToColor()`.
 *
 * ### How one gets on screen
 *
 * The corpus ships as an asset (see `tools/vendor_gl_transitions.py`, which
 * audits the licences and applies the two GLSL ES 3.00 fixes the WebGL-era
 * sources need). At selection time [spliceInto] pastes the chosen transition
 * into a copy of the composite shader and the renderer links that variant,
 * caching it. There is deliberately no attempt to compile all 123 up front:
 * that would be 123 programs of driver compile time at startup for a feature
 * where exactly one is in use at a time.
 *
 * ### Identity
 *
 * A transition is identified by a STRING id everywhere - `"fade"`, `"cut"`, or
 * a corpus name like `"DoomScreenTransition"`. The five built-ins keep their
 * [TransitionStyle] enum because the base shader implements them directly, but
 * the id is what the UI, the view-model and the renderer pass around, so the
 * two families are one flat list to everything above this file.
 */
internal object TransitionCatalog {
    /** Asset holding the vendored corpus. */
    private const val ASSET = "gl_transitions.json"

    /** Marker in `composite_frag.glsl` that a transition's source replaces. */
    private const val SOURCE_MARKER = "// __GL_TRANSITION_SOURCE__"

    /** `uStyle` value meaning "a spliced library transition is running". */
    const val STYLE_LIBRARY: Int = 5

    /** One tunable uniform of a corpus transition, with its upstream default. */
    data class Param(
        val name: String,
        val type: String,
        val values: FloatArray,
    ) {
        // FloatArray in a data class: identity equals/hashCode would make two
        // equal params compare unequal, and these end up in state comparisons.
        override fun equals(other: Any?): Boolean =
            other is Param && name == other.name && type == other.type && values.contentEquals(other.values)

        override fun hashCode(): Int = (name.hashCode() * 31 + type.hashCode()) * 31 + values.contentHashCode()
    }

    /** A corpus transition: its source, its licence and its tunable uniforms. */
    data class Def(
        val name: String,
        val author: String,
        val license: String,
        val params: List<Param>,
        val glsl: String,
    )

    /** The five styles the base composite shader implements itself. */
    val BUILT_IN_IDS: List<String> = TransitionStyle.entries.map { it.name.lowercase() }

    @Volatile
    private var library: List<Def>? = null

    /**
     * The corpus, parsed once. Safe to call from any thread; the parse is a
     * ~150 KB JSON read, so it happens off the GL thread at first use rather
     * than during a frame.
     */
    fun library(context: Context): List<Def> {
        library?.let { return it }
        val parsed =
            runCatching {
                val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
                val arr = JSONArray(text)
                (0 until arr.length()).map { i -> parseDef(arr.getJSONObject(i)) }
            }.getOrDefault(emptyList())
        library = parsed
        return parsed
    }

    /** Every selectable id, built-ins first, then the corpus alphabetically. */
    fun allIds(context: Context): List<String> = BUILT_IN_IDS + library(context).map { it.name }

    /** The corpus entry for [id], or null when [id] names a built-in style. */
    fun definition(
        context: Context,
        id: String,
    ): Def? = if (id in BUILT_IN_IDS) null else library(context).firstOrNull { it.name == id }

    /** The built-in style for [id], or null when [id] names a corpus entry. */
    fun builtIn(id: String): TransitionStyle? = TransitionStyle.entries.firstOrNull { it.name.lowercase() == id }

    /**
     * A copy of [base] with [def] spliced in: `MV_TRANSITION` defined so the
     * shader's `#ifdef` blocks come alive, and the transition's own source in
     * place of the marker.
     *
     * Mirrored by `scratchpad/splice-check.js`, which compiles every corpus
     * entry through this same transformation in a real GLSL ES 3.00 compiler -
     * the only way to know a shader written for WebGL 1 links here without
     * putting it on a device.
     */
    fun spliceInto(
        base: String,
        def: Def,
    ): String {
        val versionEnd = base.indexOf('\n') + 1
        val withDefine = base.substring(0, versionEnd) + "#define MV_TRANSITION 1\n" + base.substring(versionEnd)
        return withDefine.replace(SOURCE_MARKER, def.glsl)
    }

    /**
     * Uploads a transition's parameters at their upstream defaults.
     *
     * Every corpus transition declares its tunables as plain uniforms with a
     * `// = default` comment, which the vendoring step turns into the values
     * here, so they are set by name rather than packed into an array - a
     * transition's own source stays untouched, which is what keeps the corpus
     * re-vendorable from a newer upstream.
     */
    fun uploadParams(
        program: Int,
        def: Def,
    ) {
        for (p in def.params) {
            val loc = GLES30.glGetUniformLocation(program, p.name)
            if (loc < 0) continue
            val v = p.values
            when (p.type) {
                "float" -> GLES30.glUniform1f(loc, v.getOrElse(0) { 0f })
                "int", "bool" -> GLES30.glUniform1i(loc, v.getOrElse(0) { 0f }.toInt())
                "vec2" -> GLES30.glUniform2f(loc, v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f })
                "vec3" -> GLES30.glUniform3f(loc, v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f })
                "vec4" ->
                    GLES30.glUniform4f(
                        loc,
                        v.getOrElse(0) { 0f },
                        v.getOrElse(1) { 0f },
                        v.getOrElse(2) { 0f },
                        v.getOrElse(3) { 0f },
                    )
                "ivec2" -> GLES30.glUniform2i(loc, v.getOrElse(0) { 0f }.toInt(), v.getOrElse(1) { 0f }.toInt())
                "ivec3" ->
                    GLES30.glUniform3i(
                        loc,
                        v.getOrElse(0) { 0f }.toInt(),
                        v.getOrElse(1) { 0f }.toInt(),
                        v.getOrElse(2) { 0f }.toInt(),
                    )
            }
        }
    }

    private fun parseDef(o: JSONObject): Def {
        val types = o.optJSONObject("paramsTypes")
        val defaults = o.optJSONObject("defaultParams")
        val params =
            types?.keys()?.asSequence()?.map { key ->
                Param(key, types.optString(key, "float"), floatsOf(defaults?.opt(key)))
            }?.toList().orEmpty()
        return Def(
            name = o.optString("name"),
            author = o.optString("author"),
            license = o.optString("license"),
            params = params,
            glsl = o.optString("glsl"),
        )
    }

    /** JSON default -> uniform components; booleans and vectors both land here. */
    private fun floatsOf(value: Any?): FloatArray =
        when (value) {
            null -> floatArrayOf(0f)
            is Boolean -> floatArrayOf(if (value) 1f else 0f)
            is Number -> floatArrayOf(value.toFloat())
            is JSONArray -> FloatArray(value.length()) { i -> value.optDouble(i, 0.0).toFloat() }
            else -> floatArrayOf(0f)
        }
}
