package dev.geode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Every store in `filesDir` has a declared answer to "does this come back?".
 *
 * `backup_rules.xml` and `data_extraction_rules.xml` carry careful quota
 * arithmetic in their comments - the analysis cache, the imported textures and
 * the performance takes are excluded because leaving any one of them in pushes
 * the total past Auto Backup's ~25 MB quota, at which point the platform
 * silently stops backing up EVERYTHING, presets and playlists and history
 * included. Nothing pinned that. A new `File(context.filesDir, ...)` store is
 * two lines, ships backed up by default, and if it is big it takes the user's
 * presets down with it - with no error, on a device the author does not own.
 *
 * So this gate does not check the XML against a hardcoded list. It reads every
 * `filesDir` path out of the main source tree and requires each one to appear
 * in [PAYLOADS] with a decision: [Backup.Restored], [Backup.Excluded] with a
 * reason, or [Backup.Swept] for a directory its owner deletes on sight. Adding
 * a store fails this test until someone writes down which of the three it is,
 * and choosing "excluded" then has to be honoured in both rule files.
 *
 * It runs in the other direction too: an exclude for a store that no longer
 * exists fails here rather than sitting in the XML forever looking deliberate.
 */
class BackupRulesCoverageTest {
    /** What happens to a `filesDir` payload when the user moves to a new device. */
    private sealed interface Backup {
        /** Carried over: user-authored, or small and expensive to recompute. */
        data object Restored : Backup

        /** Deliberately dropped, for [why]: rebuildable, device-local, or quota. */
        data class Excluded(val why: String) : Backup

        /** Never lives long enough to back up - [deletedBy] removes it on sight. */
        data class Swept(val deletedBy: String) : Backup
    }

    private companion object {
        /**
         * Every path under `filesDir` the app writes, and whether it returns.
         *
         * Keyed exactly as the XML spells it, because that is what the platform
         * matches on. Subpaths of a restored tree are legal and used:
         * `milk` comes back, `milk/textures` inside it does not.
         */
        val PAYLOADS: Map<String, Backup> =
            mapOf(
                "presets" to Backup.Restored,
                "palettes" to Backup.Restored,
                "music-playlists" to Backup.Restored,
                "history.json" to Backup.Restored,
                "library.json" to Backup.Restored,
                "milk" to Backup.Restored,
                "analysis" to
                    Backup.Excluded("rebuildable by re-analysis, and the largest thing in filesDir by an order of magnitude"),
                "milk/textures" to Backup.Excluded("user-imported images, re-importable, and the quota risk the comments describe"),
                "milk/generated" to Backup.Excluded("generated .milk wrappers - TextureStore rewrites them from the textures above"),
                "takes" to Backup.Excluded("megabytes of JSON per long performance"),
                "crash-latest.txt" to Backup.Excluded("device-local debug output; restoring it would show a stale crash on a new phone"),
                "milk-builtin" to Backup.Swept("PlayerViewModel.milkPresetFilesAsync deleteRecursively"),
            )

        /**
         * Excludes the device-to-device transfer deliberately does NOT repeat.
         *
         * `device-transfer` is a local copy with no cloud quota, so carrying the
         * analysis cache and the textures across is a win - it is the difference
         * between a library that is already analysed on the new phone and one
         * that re-derives every BPM. Only the crash file is dropped, because it
         * describes hardware the user just stopped using. This asymmetry is the
         * one thing about these two files that is not "the same shape", so it is
         * declared here rather than left looking like an oversight.
         */
        val TRANSFER_KEEPS: Set<String> = setOf("analysis", "milk/textures", "milk/generated", "takes")

        /** `File(context.filesDir, "x")` in any of its spellings across main/. */
        val FILES_DIR_PATH = Regex("filesDir,\\s*\"([^\"]+)\"")

        /** An XML comment, including one spanning lines. */
        val XML_COMMENT = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    }

    @Test
    fun `every filesDir store in main sources has a declared backup decision`() {
        val undeclared =
            mainSources()
                .flatMap { text -> FILES_DIR_PATH.findAll(text).map { it.groupValues[1] } }
                .distinct()
                .filterNot { it in PAYLOADS }
                .sorted()
        assertEquals(
            "these filesDir paths are not in PAYLOADS - decide whether each is restored, excluded " +
                "or swept, and put excluded ones in BOTH rule files or the ~25 MB quota takes presets " +
                "and playlists down with them",
            emptyList<String>(),
            undeclared,
        )
    }

    @Test
    fun `every excluded payload is excluded in both rule files`() {
        val declared = PAYLOADS.filterValues { it is Backup.Excluded }.keys
        assertEquals(
            "declared excluded but still backed up by Android 11 and below",
            emptyList<String>(),
            (declared - fileExcludes(autoBackup())).sorted(),
        )
        assertEquals(
            "declared excluded but still backed up by Android 12+ cloud backup",
            emptyList<String>(),
            (declared - fileExcludes(cloudBackup())).sorted(),
        )
    }

    @Test
    fun `nothing the rule files exclude is missing from PAYLOADS`() {
        // A store deleted from the app leaves an exclude behind that reads as a
        // decision about something real. The reverse of the first test.
        val excluded = fileExcludes(autoBackup()) + fileExcludes(cloudBackup()) + fileExcludes(deviceTransfer())
        assertEquals(
            "these paths are excluded in XML but no longer written by main/ - drop the stale rule",
            emptyList<String>(),
            (excluded - PAYLOADS.keys).sorted(),
        )
    }

    @Test
    fun `no payload is both restored and excluded`() {
        // Reversing a decision in the XML without reversing it here would leave
        // the reason in PAYLOADS describing behaviour the app no longer has.
        val restored = PAYLOADS.filterValues { it is Backup.Restored }.keys
        val excluded = fileExcludes(autoBackup()) + fileExcludes(cloudBackup())
        assertEquals(
            "PAYLOADS says these come back but the rule files drop them",
            emptyList<String>(),
            restored.intersect(excluded).sorted(),
        )
    }

    @Test
    fun `the two rule files agree on the cloud policy`() {
        // One policy, two file formats for two OS version ranges. They drifted
        // once already: the pre-quota version of backup_rules.xml excluded three
        // things the cloud rules did not.
        assertEquals(
            "backup_rules.xml and data_extraction_rules.xml disagree about what leaves the device",
            fileExcludes(autoBackup()).sorted(),
            fileExcludes(cloudBackup()).sorted(),
        )
    }

    @Test
    fun `device transfer keeps exactly the payloads declared worth carrying`() {
        val transferDrops = fileExcludes(deviceTransfer())
        assertEquals(
            "device-transfer's excludes no longer match TRANSFER_KEEPS - if the local-copy reasoning " +
                "changed, change the declaration and its comment too",
            (fileExcludes(cloudBackup()) - TRANSFER_KEEPS).sorted(),
            transferDrops.sorted(),
        )
        assertEquals(
            "TRANSFER_KEEPS lists something device-transfer actually drops",
            emptySet<String>(),
            TRANSFER_KEEPS.intersect(transferDrops),
        )
    }

    @Test
    fun `a commented-out exclude does not count as an exclude`() {
        // The gate's own parser, on markup shaped like the mistake it has to
        // catch: an exclude someone disabled while debugging a restore, and a
        // comment that quotes a section tag.
        val text =
            withoutComments(
                """
                <!-- <device-transfer> keeps what <cloud-backup> drops. -->
                <cloud-backup>
                    <!-- <exclude domain="file" path="analysis" /> -->
                    <exclude domain="file" path="takes" />
                </cloud-backup>
                """.trimIndent(),
            )
        assertEquals(setOf("takes"), fileExcludes(section(text, "cloud-backup")))
    }

    @Test
    fun `both rule files are wired up in the manifest`() {
        // Neither file does anything on its own, and a typo'd resource name is a
        // build-time no-op the app never mentions again.
        val manifest = File(repoDir("src/main"), "AndroidManifest.xml").readText()
        assertTrue(
            "android:fullBackupContent must point at backup_rules",
            manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""),
        )
        assertTrue(
            "android:dataExtractionRules must point at data_extraction_rules",
            manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""),
        )
    }

    /** `<full-backup-content>`: the Android 11-and-below rules. */
    private fun autoBackup(): String = section(xml("backup_rules.xml"), "full-backup-content")

    /** `<cloud-backup>`: the Android 12+ rules for what leaves the device. */
    private fun cloudBackup(): String = section(xml("data_extraction_rules.xml"), "cloud-backup")

    /** `<device-transfer>`: the Android 12+ rules for a direct phone-to-phone copy. */
    private fun deviceTransfer(): String = section(xml("data_extraction_rules.xml"), "device-transfer")

    /** The text inside `<[tag]>…</[tag]>`. */
    private fun section(
        text: String,
        tag: String,
    ): String {
        val open = text.indexOf("<$tag>")
        val close = text.indexOf("</$tag>")
        if (open < 0 || close < 0) fail("<$tag> not found in the rule file")
        return text.substring(open + tag.length + 2, close)
    }

    /**
     * `path` of every `<exclude domain="file" …/>` in [section].
     *
     * Attributes are read independently of their order, so reformatting the XML
     * cannot quietly turn this gate off.
     */
    private fun fileExcludes(section: String): Set<String> =
        section
            .split("<exclude")
            .drop(1)
            .map { it.substringBefore("/>") }
            .filter { attribute(it, "domain") == "file" }
            .mapNotNull { attribute(it, "path") }
            .toSet()

    private fun attribute(
        tag: String,
        name: String,
    ): String? = Regex("$name\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

    /**
     * The rule file with its comments removed.
     *
     * Both rule files are more comment than rule, and the comments quote the
     * markup they explain - so a naive read finds `<device-transfer>` in a
     * sentence about it, and a commented-out `<exclude>` counts as an active
     * one. Stripping first makes both impossible; `a commented-out exclude does
     * not count` pins it.
     */
    private fun xml(name: String): String =
        withoutComments(
            File(repoDir("src/main/res/xml"), name).also {
                if (!it.isFile) fail("$name not found")
            }.readText(),
        )

    private fun withoutComments(text: String): String = text.replace(XML_COMMENT, "")

    /** Every main-source Kotlin file's text. */
    private fun mainSources(): List<String> =
        repoDir("src/main/java/dev/geode")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .toList()

    /** Resolves a directory under `app/`, whichever directory the tests run from. */
    private fun repoDir(relative: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            for (prefix in listOf("", "app/")) {
                val candidate = File(dir, "$prefix$relative")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        fail("$relative not found from ${File("").absolutePath}")
        error("unreachable")
    }
}
