package dev.musicviz

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.musicviz.render.scene.SceneParams
import dev.musicviz.ui.PerformanceTake
import dev.musicviz.ui.PlayerViewModel
import dev.musicviz.ui.TakeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The Takes tab must tell the truth about recording.
 *
 * Three lies pinned shut here:
 *  - a stop that DISCARDED a one-keyframe take looked exactly like a save
 *    (the discard is right - a still has nothing to replay - the silence was
 *    not), so it now leaves a transient note in [dev.musicviz.ui.TakeUiState];
 *  - the recording clock only advanced on parameter traffic, so an untouched
 *    recording read 0:00 for its whole length - a 1 s ticker now drives it;
 *  - renameTake dropped [TakeStore.rename]'s answer, so a rename the store
 *    refused closed the dialog as if it had happened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TakeRecordingUxTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    private fun vm(): PlayerViewModel = PlayerViewModel(app)

    private fun savedTake(name: String) {
        TakeStore(app).save(
            name,
            PerformanceTake
                .Recorder("fluid", SceneParams.DEFAULT, null)
                .finish(name, null, 0L),
        )
    }

    @Test
    fun `discarding a one-keyframe take says so instead of silently not saving`() {
        val v = vm()
        v.startRecording()
        // Nothing moved: the recorder holds only its opening keyframe.
        v.stopRecording()
        val note = v.takeState.value.note
        assertNotNull("a discarded take left no user-visible note", note)
        assertTrue("note does not say the take was not saved: \"$note\"", note!!.contains("take not saved"))
        assertFalse(v.takeState.value.recording)
    }

    @Test
    fun `the discard note clears itself and the next recording clears it early`() {
        val v = vm()
        v.startRecording()
        v.stopRecording()
        assertNotNull(v.takeState.value.note)
        // Transient by timer...
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(4_500))
        assertNull("the note never cleared itself", v.takeState.value.note)
        // ...and by the next recording, whichever comes first.
        v.startRecording()
        v.stopRecording()
        assertNotNull(v.takeState.value.note)
        v.startRecording()
        assertNull(v.takeState.value.note)
        v.stopRecording()
    }

    @Test
    fun `the recording clock advances without any parameter traffic`() {
        val v = vm()
        v.startRecording()
        assertEquals(0L, v.takeState.value.recordedMs)
        // No slider moves, no scene switches - only time passing. The looper
        // idles drive Robolectric's clock, which is what the ticker reads.
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(3_100))
        assertTrue(
            "recordedMs=${v.takeState.value.recordedMs} after ~3 s of untouched recording",
            v.takeState.value.recordedMs >= 2_000L,
        )
        v.stopRecording()
    }

    @Test
    fun `renameTake surfaces the store's answer`() {
        val v = vm()
        savedTake("Alpha")
        savedTake("Gamma")
        assertTrue("a clean rename reported failure", v.renameTake("Alpha", "Beta"))
        // The dialog gates predictable collisions, but the store is the
        // contract - and its refusal must reach the caller.
        assertFalse("renaming onto an existing take reported success", v.renameTake("Beta", "Gamma"))
        assertFalse("renaming a take that does not exist reported success", v.renameTake("Nobody", "Anyone"))
        assertFalse("renaming to blank reported success", v.renameTake("Beta", "   "))
    }
}
