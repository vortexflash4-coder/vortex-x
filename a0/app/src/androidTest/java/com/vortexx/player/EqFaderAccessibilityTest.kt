package com.vortexx.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.vortexx.player.ui.designsystem.instrument.EqFaderBank
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class EqFaderAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactBankScrollsToLastBandWithSeparateTouchTargetsAndTalkBackRange() {
        var changedBand = -1
        var changedGain = 0f
        compose.setContent {
            Box(Modifier.width(320.dp)) {
                EqFaderBank(List(10) { 0f }, 48_000, { band, gain -> changedBand = band; changedGain = gain })
            }
        }
        compose.onNodeWithContentDescription("31 hertz band")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("16k hertz band").performScrollTo().assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(6f) }
        compose.runOnIdle { assertEquals(9, changedBand); assertEquals(6f, changedGain, 0f) }
    }
}
