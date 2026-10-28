package com.sabvisa.local

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.sabvisa.core.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class DashboardTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Before fun seedSyntheticBook() {
        val opening=Instant.now().minusSeconds(2*86400)
        Vault.get(compose.activity).change {
            VaultData(Book(Config(1_200_000,35_000,opening,"9876",budget=100_000),
                plans=listOf(Plan("synthetic-plan","خطة تجريبية",420_000,12,LocalDate.now(RIYADH).plusDays(5),opening)),
                entries=listOf(Entry("synthetic-buy",Kind.PURCHASE,10_000,opening.plusSeconds(3600),"شراء تجريبي"),
                    Entry("synthetic-payment",Kind.PAYMENT,5_000,opening.plusSeconds(7200),"سداد تجريبي")),
                checkpoints=listOf(Checkpoint("synthetic-balance",opening.plusSeconds(7200),740_000,"synthetic bank"))))
        }
    }
    @After fun cleanup() { Vault.get(compose.activity).wipe() }
    @Test fun dashboardAndTabsShowSyntheticFinancialState() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("7400.00 ر.س").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("7400.00 ر.س").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("إضافة شراء").assertDoesNotExist()
        compose.onNodeWithText("تسجيل سداد").assertDoesNotExist()
        compose.onNodeWithText("الأقساط",useUnmergedTree=true).performClick()
        compose.onNodeWithText("خطة تجريبية").assertExists()
        compose.onNodeWithText("السجل",useUnmergedTree=true).performClick()
        compose.onNodeWithText("شراء تجريبي").assertExists()
        compose.onNodeWithText("الإعدادات",useUnmergedTree=true).performClick()
        compose.onNodeWithText("الخصوصية").assertExists()
        compose.onNodeWithTag("test-notification").assertExists()
        compose.onNodeWithTag("connect-updates").performScrollTo().performClick()
        compose.onNodeWithTag("update-token").assertExists()
        compose.onNodeWithText("إلغاء",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("change-limit").performScrollTo().performClick()
        compose.onNodeWithTag("limit").performTextReplacement("15000")
        compose.onNodeWithText("حفظ",useUnmergedTree=true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("15000.00 ر.س").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("change-cycle").performScrollTo().performClick()
        compose.onNodeWithTag("day").performTextReplacement("31")
        compose.onNodeWithText("حفظ",useUnmergedTree=true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("يوم الإقفال المسجل: 31").fetchSemanticsNodes().isNotEmpty() }
    }
}
