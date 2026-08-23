package dev.geode.ui

import android.content.SharedPreferences
import dev.geode.data.LfoStore
import dev.geode.render.AdsrConfig
import dev.geode.render.AdsrEngine
import dev.geode.render.LfoConfig
import dev.geode.render.scene.CustomizeTab
import dev.geode.render.scene.ParamRandomizer
import dev.geode.render.scene.SceneParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

internal class ModulationController(
    private val lfoStore: LfoStore,
    private val prefs: SharedPreferences,
    private val host: Host,
) {
    interface Host {
        val params: SceneParams

        fun setSceneParams(params: SceneParams)
    }

    private val _lfos = MutableStateFlow(lfoStore.load())
    val lfos: StateFlow<List<LfoConfig>> = _lfos

    private val _adsrs = MutableStateFlow(lfoStore.loadAdsrs())
    val adsrs: StateFlow<List<AdsrConfig>> = _adsrs

    private val _lockedParams =
        MutableStateFlow<Set<String>>(prefs.getStringSet("locked_params", emptySet()) ?: emptySet())
    val lockedParams: StateFlow<Set<String>> = _lockedParams

    fun toggleParamLock(label: String) {
        _lockedParams.update { if (label in it) it - label else it + label }
        prefs.edit().putStringSet("locked_params", _lockedParams.value).apply()
    }

    fun randomizeParams(tab: CustomizeTab? = null) {
        host.setSceneParams(ParamRandomizer.randomize(host.params, _lockedParams.value, tab = tab))
    }

    fun setAdsr(
        index: Int,
        config: AdsrConfig,
    ) {
        val list = _adsrs.value.toMutableList()
        while (list.size < AdsrEngine.COUNT) list.add(AdsrConfig())
        if (index in list.indices) {
            list[index] = config
            _adsrs.value = list
            lfoStore.saveAdsrs(list)
        }
    }

    fun setLfo(
        index: Int,
        config: LfoConfig,
    ) {
        val list = _lfos.value.toMutableList()
        while (list.size < LFO_COUNT) list.add(LfoConfig())
        if (index in 0 until LFO_COUNT) {
            list[index] = config
            _lfos.value = list
            lfoStore.save(list)
        }
    }

    private companion object {
        const val LFO_COUNT = 3
    }
}
