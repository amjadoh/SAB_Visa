package com.sabvisa.local

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class OnboardingTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun privacyAndSetupAreVisible() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("setup").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("setup").performClick()
        compose.onNodeWithTag("last4").assertExists()
        compose.onNodeWithTag("limit").assertExists()
        compose.onNodeWithText("إلغاء").performClick()
    }
}
