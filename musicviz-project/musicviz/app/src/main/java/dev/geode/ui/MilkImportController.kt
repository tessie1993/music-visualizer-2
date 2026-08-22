package dev.geode.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import dev.geode.RingLog
import dev.geode.data.AtomicWrite
import dev.geode.data.MilkPackImporter
import dev.geode.data.PresetStore
import dev.geode.render.scene.MilkStarterPack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal class MilkImportController(
    private val application: Application,
    private val scope: CoroutineScope,
) {
    private fun builtInDir(): File = File(application.filesDir, "milk-builtin")

    private fun importDir(): File = File(application.filesDir, "milk")

    @Suppress("TooGenericExceptionCaught")
    fun milkPresetFilesAsync(onDone: (List<MilkFile>) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val files =
                try {
                    builtInDir().deleteRecursively()
                    File(importDir(), "textures").mkdirs()
                    MilkStarterPack.install(application, importDir())
                    importDir()
                        .listFiles { f -> f.extension == "milk" }
                        .orEmpty()
                        .map { MilkFile(it.nameWithoutExtension, it.absolutePath) }
                        .sortedBy { it.name }
                } catch (t: Throwable) {
                    RingLog.note("MilkFiles", "milk list failed", t)
                    emptyList()
                }
            withContext(Dispatchers.Main) { onDone(files) }
        }
    }

    fun importMilkPresetAsync(
        uri: Uri,
        onDone: (String?) -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            val path = importMilkPresetBlocking(uri)
            withContext(Dispatchers.Main) { onDone(path) }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun importMilkPresetBlocking(uri: Uri): String? =
        try {
            val dir = importDir().apply { mkdirs() }
            val display = displayNameOf(uri).orEmpty().ifBlank { "preset" }
            val file = File(dir, PresetStore.milkFileName(display))
            val written =
                application.contentResolver.openInputStream(uri)?.use { input ->
                    AtomicWrite.stream(file) { out -> input.copyTo(out) }
                } ?: false
            if (written) file.absolutePath else null
        } catch (t: Throwable) {
            RingLog.note("MilkImport", "milk import failed", t)
            null
        }

    fun importMilkFolderAsync(
        treeUri: Uri,
        onDone: (MilkPackImporter.Report) -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            val entries = mutableListOf<MilkPackImporter.Entry>()
            runCatching {
                val root = DocumentFile.fromTreeUri(application, treeUri)
                if (root != null) collectMilkEntries(root, entries, depth = 0)
            }
            val report = MilkPackImporter.import(entries, importDir())
            withContext(Dispatchers.Main) { onDone(report) }
        }
    }

    private fun collectMilkEntries(
        dir: DocumentFile,
        out: MutableList<MilkPackImporter.Entry>,
        depth: Int,
    ) {
        if (depth > MILK_WALK_DEPTH) return
        for (child in dir.listFiles()) {
            when {
                child.isDirectory -> collectMilkEntries(child, out, depth + 1)
                child.isFile -> {
                    val name = child.name ?: continue
                    val uri = child.uri
                    out +=
                        MilkPackImporter.Entry(name) {
                            runCatching { application.contentResolver.openInputStream(uri) }.getOrNull()
                        }
                }
            }
        }
    }

    private fun displayNameOf(uri: Uri): String? =
        runCatching {
            application.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    private companion object {
        const val MILK_WALK_DEPTH = 4
    }
}
