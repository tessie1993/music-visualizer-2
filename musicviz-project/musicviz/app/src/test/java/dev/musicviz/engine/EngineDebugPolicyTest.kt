package dev.musicviz.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slice 0.2b. The generation selector can put the app on an engine that is not
 * the shipped one, so it must not reach a release build.
 *
 * The visibility rule is a pure function rather than a bare `if
 * (BuildConfig.DEBUG)` at the call site precisely so it can be tested: unit
 * tests run against the debug variant, where `BuildConfig.DEBUG` is always
 * true, and a test that cannot observe the release case proves nothing.
 * `AppSettingsTabSplitTest` pins the call site to pass `BuildConfig.DEBUG`.
 */
class EngineDebugPolicyTest {
    @Test
    fun `a release build never shows engine controls`() {
        assertFalse(engineControlsVisible(isDebugBuild = false))
    }

    @Test
    fun `a debug build shows engine controls`() {
        assertTrue(engineControlsVisible(isDebugBuild = true))
    }
}
