package dev.musicviz.data

import android.content.Context
import dev.musicviz.render.AdsrConfig
import dev.musicviz.render.AdsrEngine
import dev.musicviz.render.EnvBand
import dev.musicviz.render.LfoConfig
import dev.musicviz.render.LfoTarget
import dev.musicviz.render.LfoWave
import org.json.JSONArray
import org.json.JSONObject

/** Persists the three LFO slots in shared preferences as JSON. */
class LfoStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("musicviz-prefs", Context.MODE_PRIVATE)

    fun load(): List<LfoConfig> =
        runCatching {
            val raw = prefs.getString(KEY, null) ?: return defaultList()
            val arr = JSONArray(raw)
            (0 until 3).map { i ->
                if (i < arr.length()) fromJson(arr.getJSONObject(i)) else LfoConfig()
            }
        }.getOrDefault(defaultList())

    fun save(lfos: List<LfoConfig>) {
        val arr = JSONArray()
        lfos.take(3).forEach { arr.put(toJson(it)) }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private fun defaultList() = List(3) { LfoConfig() }

    fun loadAdsrs(): List<AdsrConfig> =
        runCatching {
            val raw = prefs.getString(KEY_ADSR, null) ?: return List(AdsrEngine.COUNT) { AdsrConfig() }
            val arr = JSONArray(raw)
            (0 until AdsrEngine.COUNT).map { i ->
                if (i < arr.length()) adsrFromJson(arr.getJSONObject(i)) else AdsrConfig()
            }
        }.getOrDefault(List(AdsrEngine.COUNT) { AdsrConfig() })

    fun saveAdsrs(envs: List<AdsrConfig>) {
        val arr = JSONArray()
        envs.take(AdsrEngine.COUNT).forEach { arr.put(adsrToJson(it)) }
        prefs.edit().putString(KEY_ADSR, arr.toString()).apply()
    }

    private fun adsrToJson(c: AdsrConfig): JSONObject =
        JSONObject()
            .put("enabled", c.enabled)
            .put("targets", JSONArray().also { a -> c.targets.forEach { a.put(it.name) } })
            .put("attack", c.attack.toDouble())
            .put("decay", c.decay.toDouble())
            .put("sustain", c.sustain.toDouble())
            .put("release", c.release.toDouble())
            .put("amount", c.amount.toDouble())
            .put("band", c.band.name)
            .put("gateThreshold", c.gateThreshold.toDouble())
            .put("sustainTrack", c.sustainTrack)
            .put("retrigger", c.retrigger)

    private fun adsrFromJson(o: JSONObject): AdsrConfig =
        AdsrConfig(
            enabled = o.optBoolean("enabled", false),
            targets =
                buildList {
                    val a = o.optJSONArray("targets") ?: JSONArray()
                    for (i in 0 until a.length()) {
                        runCatching { LfoTarget.valueOf(a.getString(i)) }.getOrNull()?.let { add(it) }
                    }
                },
            attack = o.optDouble("attack", 0.05).toFloat(),
            decay = o.optDouble("decay", 0.25).toFloat(),
            sustain = o.optDouble("sustain", 0.5).toFloat(),
            release = o.optDouble("release", 0.35).toFloat(),
            amount = o.optDouble("amount", 0.5).toFloat(),
            band = runCatching { EnvBand.valueOf(o.getString("band")) }.getOrDefault(EnvBand.BASS),
            gateThreshold = o.optDouble("gateThreshold", 0.25).toFloat(),
            sustainTrack = o.optBoolean("sustainTrack", false),
            retrigger = o.optBoolean("retrigger", true),
        )

    private fun toJson(c: LfoConfig): JSONObject =
        JSONObject()
            .put("enabled", c.enabled)
            .put("target", c.target.name)
            .put("wave", c.wave.name)
            .put("rateHz", c.rateHz.toDouble())
            .put("beatSync", c.beatSync)
            .put("beatDiv", c.beatDiv.toDouble())
            .put("depth", c.depth.toDouble())

    private fun fromJson(o: JSONObject): LfoConfig =
        LfoConfig(
            enabled = o.optBoolean("enabled", false),
            target = runCatching { LfoTarget.valueOf(o.getString("target")) }.getOrDefault(LfoTarget.NONE),
            wave = runCatching { LfoWave.valueOf(o.getString("wave")) }.getOrDefault(LfoWave.SINE),
            rateHz = o.optDouble("rateHz", 0.5).toFloat(),
            beatSync = o.optBoolean("beatSync", false),
            beatDiv = o.optDouble("beatDiv", 1.0).toFloat(),
            depth = o.optDouble("depth", 0.3).toFloat(),
        )

    private companion object {
        const val KEY = "lfos"
        const val KEY_ADSR = "adsrs"
    }
}
