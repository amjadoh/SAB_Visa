package com.sabvisa.local

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.sabvisa.core.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
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
                    Entry("synthetic-payment",Kind.PAYMENT,5_000,opening.plusSeconds(7200),"سداد تجريبي"))))
        }
    }
    @After fun cleanup() { Vault.get(compose.activity).wipe() }
    @Test fun dashboardAndTabsShowSyntheticFinancialState() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("7400.00 ر.س").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("7400.00 ر.س").assertIsDisplayed()
        val folder=File(compose.activity.getExternalFilesDir(null),"test-previews").apply { mkdirs() }
        File(folder,"dashboard-synthetic.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
        // AGP uninstalls the test app afterward, so move this synthetic preview out of app storage now.
        val command="mkdir -p /data/local/tmp/sab-visa-test-previews && cp '${folder.absolutePath}/dashboard-synthetic.png' /data/local/tmp/sab-visa-test-previews/dashboard-synthetic.png && echo OK"
        val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        val copied=ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { String(it.readBytes(),Charsets.UTF_8).trim() }
        check(copied=="OK") { "تعذر جمع صورة الاختبار الاصطناعية" }
        compose.onNodeWithText("الأقساط",useUnmergedTree=true).performClick()
        compose.onNodeWithText("خطة تجريبية").assertExists()
        compose.onNodeWithText("السجل",useUnmergedTree=true).performClick()
        compose.onNodeWithText("شراء تجريبي").assertExists()
        compose.onNodeWithText("الإعدادات",useUnmergedTree=true).performClick()
        compose.onNodeWithText("الخصوصية").assertExists()
    }
}
