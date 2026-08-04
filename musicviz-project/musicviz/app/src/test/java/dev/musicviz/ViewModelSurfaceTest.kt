package dev.musicviz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every setting the view model exposes has to be reachable from a control.
 *
 * A `set…` function on `PlayerViewModel` exists for one reason: something the
 * user touches drives it. When nothing does, the state behind it is not broken
 * and not slow - it simply is not there, and says nothing about being missing.
 * Nine were in that state at once: Random mode's entire configuration (how
 * often it hops, whether it waits for a beat, whether it rolls styles, saved
 * presets or .milk files, whether colours come with it) and the visual
 * playlist's. All of it built, all of it read by the stepper, none of it
 * wired. `setRandomEnabled` WAS reachable, which is what made it hard to see:
 * Random could be switched on and then not influenced in any way.
 *
 * So the setters are read back out of the source and each is required to have
 * a call site under `ui/` - the package every composable lives in, and so the
 * only place a call site means "a user can get at this". The next setter added
 * without a control fails the build rather than waiting for the next audit.
 *
 * Parsing source rather than using reflection follows [ParamSurface] and
 * `ParticleGatingTest`: the question is about the CODE - does a composable
 * name this function - and nothing in the runtime objects records that.
 *
 * The setter family is the scope on purpose. The rest of the public surface is
 * a mixed bag this rule cannot judge: readers the view model calls itself
 * (`queueTitles`, `currentTrackKey`) carry no "a user can get at this" claim.
 * The music-playlist CRUD group used to sit out here too, unwired by decision
 * — and someone acted on it: the Playlists tab creates, deletes, renames and
 * reorders, the queue panel saves, and the track menus add. It graduated into
 * [playlistCrud] below, pinned the same way as the setters.
 */
class ViewModelSurfaceTest {
    /**
     * Setters that correctly have no control of their own, and why.
     *
     * Checked both ways: an entry for a setter that IS wired is a stale claim
     * about behaviour that has since changed, and fails just like an omission.
     */
    private val drivenWithoutTheirOwnControl =
        mapOf(
            "setRandomEnabled" to
                "cycleAutoMode owns it: the four auto modes are opposite instructions, so one " +
                "control cycles them rather than four switches contradicting each other",
            "setSectionStaging" to "the same cycle",
            "setTransitionStyle" to
                "superseded by setTransitionId, which takes corpus transitions too and keeps this " +
                "one's enum in step; the transition picker calls that",
        )

    /** The feature this test was written for, named so a rewrite cannot lose it. */
    private val randomAndVisualPlaylistSetters =
        listOf(
            "setRandomInterval",
            "setRandomOnBeat",
            "setRandomIncludeStyles",
            "setRandomIncludePresets",
            "setRandomIncludeMilk",
            "setRandomizeColors",
            "setVizPlaylistEnabled",
            "setVizPlaylistInterval",
            "setVizPlaylistIntelligent",
        )

    /**
     * The music-playlist CRUD group, wired by the create/save/add/delete
     * affordances and held wired here: ripping out one of those controls
     * fails this list instead of quietly re-orphaning the whole feature the
     * way it shipped the first time.
     */
    private val playlistCrud =
        listOf(
            "createMusicPlaylist",
            "renameMusicPlaylist",
            "deleteMusicPlaylist",
            "addTrackToPlaylist",
            "removeTrackFromPlaylist",
            "moveMusicPlaylistTrack",
        )

    @Test
    fun thePlaylistCrudGroupHasControls() {
        assertEquals(
            "playlist operations with no control anywhere in ui/",
            emptyList<String>(),
            playlistCrud.filterNot { Regex("\\b$it\\b").containsMatchIn(uiSources) },
        )
    }

    @Test
    fun theRandomAndVisualPlaylistSettingsHaveControls() {
        assertEquals(
            "auto-visuals settings with no control anywhere in ui/",
            emptyList<String>(),
            randomAndVisualPlaylistSetters.filterNot { it in calledFromUi },
        )
    }

    @Test
    fun everyPublicSetterHasAControl() {
        assertTrue("no setters found - has PlayerViewModel.kt moved?", publicSetters.size > 20)
        assertEquals(
            "view-model setters no control in ui/ calls",
            emptyList<String>(),
            (publicSetters - calledFromUi - drivenWithoutTheirOwnControl.keys).sorted(),
        )
        assertEquals(
            "setters declared control-less that a control does call",
            emptyList<String>(),
            (drivenWithoutTheirOwnControl.keys intersect calledFromUi).sorted(),
        )
    }

    @Test
    fun theIntervalSlidersAgreeWithTheClampsBehindThem() {
        // A slider whose range outruns its setter's coerceIn is a control that
        // stops responding partway along with nothing on screen to say why.
        // Both auto-visuals intervals clamp to 5..300, so both sliders ask for
        // exactly that - and this fails from either end if one of them moves.
        val vm = source("ui/PlayerViewModel.kt")
        listOf("setRandomInterval", "setVizPlaylistInterval").forEach { setter ->
            assertTrue(
                "$setter no longer clamps to 5..300, which its slider still assumes",
                Regex("fun $setter\\([^)]*\\)\\s*\\{[^}]*coerceIn\\(5, 300\\)").containsMatchIn(vm),
            )
        }
        assertEquals(
            "sliders in ui/ spanning the auto-visuals interval range",
            2,
            Regex("valueRange = 5f\\.\\.300f").findAll(uiSources).count(),
        )
    }

    /** Public `set…` members of `PlayerViewModel`, in declaration order. */
    private val publicSetters: List<String> by lazy {
        Regex("(?m)^    fun (set[A-Z]\\w*)\\(")
            .findAll(source("ui/PlayerViewModel.kt"))
            .map { it.groupValues[1] }
            .toList()
    }

    /** Every `ui/` source except the view model itself, concatenated. */
    private val uiSources: String by lazy {
        val dir = File(ParamSurface.moduleRoot, "app/src/main/java/dev/musicviz/ui")
        val text =
            dir
                .listFiles { f -> f.isFile && f.extension == "kt" && f.name != "PlayerViewModel.kt" }
                .orEmpty()
                .joinToString("\n") { it.readText() }
        assertTrue("no ui/ sources found under ${dir.absolutePath}", text.isNotEmpty())
        text
    }

    /**
     * Setters some composable names.
     *
     * Deliberately blind to HOW: `viewModel::setRandomOnBeat` and
     * `viewModel.setRandomInterval(…)` are the same fact, and a test that
     * insisted on one shape would fail on a refactor that changed nothing.
     */
    private val calledFromUi: Set<String> by lazy {
        publicSetters.filter { Regex("\\b$it\\b").containsMatchIn(uiSources) }.toSet()
    }

    private fun source(relative: String): String = ParamSurface.source(relative)
}
