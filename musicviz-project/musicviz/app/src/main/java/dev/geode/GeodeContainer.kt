package dev.geode

import android.content.Context
import dev.geode.data.GeodePrefsFiles
import dev.geode.ui.SharedPrefsUserDataRepository
import dev.geode.ui.UserDataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class GeodeContainer(
    context: Context,
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val prefsFiles = GeodePrefsFiles(context)

    val userData: UserDataRepository = SharedPrefsUserDataRepository(prefsFiles.general, appScope)
}

val Context.geodeContainer: GeodeContainer
    get() = (applicationContext as GeodeApp).container
