package com.vortexx.player

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.vortexx.player.core.permissions.MediaPermissions
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

/**
 * Smoke test across the main destinations with media permissions pre-granted.
 * Written for phone-sized emulators (bottom navigation). Rotation and tablet
 * layouts are covered manually; see docs/TESTING.md.
 */
@RunWith(AndroidJUnit4::class)
class AppNavigationTest {

    private val permissions: GrantPermissionRule = GrantPermissionRule.grant(*MediaPermissions.all)
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(permissions).around(composeRule)

    @Test
    fun bottomNavigationReachesEachLibrary() {
        composeRule.onNodeWithText("VORTEX X").assertExists()

        composeRule.onNodeWithText("Music").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Search songs, artists, albums").assertExists()

        composeRule.onNodeWithText("Videos").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Search videos and folders").assertExists()

        composeRule.onNodeWithText("Explorer").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Open a file").assertExists()
    }

    @Test
    fun settingsShowOnlyWorkingControlsAndSurviveModeChange() {
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTextCount("Search settings") > 0
        }
        // Basic mode hides advanced controls.
        composeRule.onNodeWithText("Basic").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Short clip threshold").assertDoesNotExist()

        composeRule.onNodeWithText("Developer").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Short clip threshold").assertExists()

        composeRule.onNodeWithText("Basic").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Short clip threshold").assertDoesNotExist()
    }
}

private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
