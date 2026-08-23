package dev.geode.ui

import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

object GeodeDestinations {
    const val PLAYER = 0
    const val LIBRARY = 1
    const val VISUALS = 2
    const val STUDIO = 3
    const val SETTINGS = 4
}

@Stable
class GeodeAppState(
    dest: Int = GeodeDestinations.PLAYER,
    expanded: Boolean = false,
    searching: Boolean = false,
    bootDone: Boolean = false,
) {
    var dest by mutableIntStateOf(dest)

    var expanded by mutableStateOf(expanded)

    var searching by mutableStateOf(searching)

    var bootDone by mutableStateOf(bootDone)

    var externalDisplay: Display? = null
        internal set

    val onPlayer: Boolean get() = dest == GeodeDestinations.PLAYER

    fun openSearch() {
        searching = true
    }

    fun closeSearch() {
        searching = false
    }

    fun expand() {
        expanded = true
    }

    fun collapse() {
        expanded = false
    }

    fun navigateTo(destination: Int) {
        dest = destination
    }

    fun resetToPlayer() {
        dest = GeodeDestinations.PLAYER
    }

    companion object {
        val Saver: Saver<GeodeAppState, List<Any>> =
            Saver(
                save = { listOf(it.dest, it.expanded, it.searching, it.bootDone) },
                restore = {
                    GeodeAppState(
                        dest = it[0] as Int,
                        expanded = it[1] as Boolean,
                        searching = it[2] as Boolean,
                        bootDone = it[3] as Boolean,
                    )
                },
            )
    }
}

@Composable
fun rememberGeodeAppState(externalDisplay: Display?): GeodeAppState {
    val state = rememberSaveable(saver = GeodeAppState.Saver) { GeodeAppState() }
    state.externalDisplay = externalDisplay
    return state
}
