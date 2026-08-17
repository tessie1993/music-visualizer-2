package dev.geode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Nothing that unlocks an account may be committable.
 *
 * The upload feature needs OAuth client ids and secrets for YouTube, TikTok and
 * Instagram. The moment those exist on a machine they are one `git add -A` from
 * being public, and a leaked client secret is a rotation plus a store
 * re-verification — not an inconvenience. A `.gitignore` line is the whole
 * defence, and a `.gitignore` line is exactly the kind of thing a later commit
 * reorganises away without anyone noticing.
 *
 * So the rule is a test. It checks two things a reviewer cannot check by
 * reading a diff: that the ignore patterns are actually present, and that no
 * file matching them ever got tracked.
 */
class SecretHygieneTest {
    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, ".git").exists() }
            ?: error("repository root not found from ${File("").absolutePath}")

    private val moduleRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/res/values/strings.xml").isFile }
            ?: error("module root not found from ${File("").absolutePath}")

    /**
     * Patterns that must be ignored somewhere on the path from the module up.
     *
     * Keystore material was already covered; the credential files are the new
     * half, added before the feature that needs them rather than alongside it,
     * because the commit that introduces the first `.env` is the one nobody
     * reads carefully.
     */
    private val mustBeIgnored =
        listOf(
            ".env",
            "keystore.properties",
            "*.jks",
            "*.keystore",
            "secrets.properties",
            "google-services.json",
            "local.properties",
        )

    private val ignoreText: String by lazy {
        listOf(File(repoRoot, ".gitignore"), File(moduleRoot, ".gitignore"))
            .filter { it.isFile }
            .joinToString("\n") { it.readText() }
    }

    @Test
    fun `every kind of credential file is ignored`() {
        val missing = mustBeIgnored.filterNot { pattern -> ignoreText.lineSequence().any { it.trim() == pattern } }
        assertEquals("these can be committed by accident", emptyList<String>(), missing)
    }

    /**
     * The ignore rules only protect files that were never added. A file already
     * tracked stays tracked no matter what `.gitignore` says, so the tree
     * itself has to be checked.
     */
    @Test
    fun `no credential file was ever committed`() {
        val suspicious =
            repoRoot
                .walkTopDown()
                .onEnter { it.name != ".git" && it.name != "build" && it.name != ".gradle" }
                .filter { it.isFile }
                .filter { f ->
                    val n = f.name
                    n == ".env" ||
                        n.startsWith(".env.") && n != ".env.example" ||
                        n.endsWith(".jks") ||
                        n.endsWith(".keystore") ||
                        n == "keystore.properties" ||
                        n == "secrets.properties" ||
                        n == "google-services.json"
                }.map { it.relativeTo(repoRoot).path }
                .toList()
        assertTrue("credential material is present in the working tree: $suspicious", suspicious.isEmpty())
    }

    /**
     * A secret pasted into Kotlin bypasses every ignore rule there is.
     *
     * Deliberately narrow: it looks for the shapes that are unambiguously keys
     * (Google's `AIza…`, a Slack token, a PEM block, an AWS id) rather than for
     * the word "secret", which appears in prose all over this tree.
     */
    @Test
    fun `no key material is pasted into the sources`() {
        val shapes =
            listOf(
                Regex("""AIza[0-9A-Za-z_\-]{35}"""),
                Regex("""xox[baprs]-[0-9A-Za-z\-]{10,}"""),
                Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
                Regex("""AKIA[0-9A-Z]{16}"""),
                Regex("""ghp_[0-9A-Za-z]{36}"""),
            )
        val hits =
            File(moduleRoot, "app/src")
                .walkTopDown()
                .filter { it.isFile && it.extension in setOf("kt", "java", "xml", "properties", "json") }
                .flatMap { f ->
                    val text = f.readText()
                    shapes.filter { it.containsMatchIn(text) }.map { "${f.relativeTo(moduleRoot).path}: ${it.pattern}" }
                }.toList()
        assertEquals("key material in version control", emptyList<String>(), hits)
    }
}
