package com.sabvisa.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sabvisa.core.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant

/** Run only on the dedicated test emulator; uses the test app's isolated storage. */
@RunWith(AndroidJUnit4::class)
class VaultTest {
    private lateinit var context: Context
    private lateinit var vault: Vault
    @Before fun setup() {
        context=ApplicationProvider.getApplicationContext(); vault=Vault.get(context); vault.wipe()
    }
    @After fun cleanup() { vault.wipe() }
    @Test fun encryptedRoundTripDuplicateImportAndCorruptionFailClosed() {
        val start=Instant.parse("2026-01-01T00:00:00Z")
        vault.change { VaultData(Book(Config(1000000,0,start,"9876"))) }
        val at=start.plusSeconds(86400)
        val body="شراء بطاقة Visa (9876) لدى SYNTHETIC بمبلغ SAR 42.75 الرصيد: SAR 6543.21"
        assertTrue(vault.ingest("SAB",body,at)); assertFalse(vault.ingest("SAB",body,at))
        assertFalse(vault.ingest("SAB","رمز التحقق: 1234",at))
        assertEquals(1,vault.load().inbox.size)
        assertEquals("AUTO",vault.load().inbox.single().status)
        assertEquals(1,vault.load().book!!.entries.size)
        assertEquals(654321L,vault.load().book!!.liveBalance(Instant.now())!!.available)
        val file=File(context.noBackupFilesDir,"ledger.v1.enc")
        assertFalse(String(file.readBytes(),Charsets.ISO_8859_1).contains("SYNTHETIC"))
        val original=file.readBytes(); val tampered=original.clone(); tampered[tampered.lastIndex]=(tampered.last().toInt() xor 1).toByte(); file.writeBytes(tampered)
        try { vault.load(); fail("Tampered ciphertext must fail") } catch(_: Exception) { }
        assertArrayEquals(tampered,file.readBytes())
        file.writeBytes(original); assertEquals(1,vault.load().inbox.size)
    }
    @Test fun rejectedMutationDoesNotOverwriteLedger() {
        val start=Instant.parse("2026-01-01T00:00:00Z")
        vault.change { VaultData(Book(Config(1000000,0,start,"9876"))) }
        try { vault.change { it.copy(book=it.book!!.copy(entries=listOf(Entry(id(),Kind.PURCHASE,100,start,"invalid")))) }; fail("expected") } catch(_: IllegalArgumentException) { }
        assertTrue(vault.load().book!!.entries.isEmpty())
    }
    @Test fun installmentMessagePaymentAndLimitChangeSurviveReloadWithoutDuplicates() {
        val start=Instant.parse("2026-01-01T00:00:00Z")
        vault.change { VaultData(Book(Config(1000000,0,start,"9876"))) }
        val at=start.plusSeconds(20*86400)
        val plan="تم تحويل العملية الشرائية بمبلغ س.ر 2,400.00 على البطاقة الائتمانية9876 الى12 قسط شهرى, مبلغ القسط س.ر 200.00, الربح الشهرى .00%و0 رسوم ادارية."
        assertTrue(vault.ingest("SAB",plan,at))
        assertFalse(vault.ingest("SAB",plan,at.plusSeconds(60)))
        val payment="بطاقة ائتمانية: تأكيد السداد تم سداد بطاقتك Visa (***9876) بمبلغ SAR 500.00 في: 2026-01-22 12:00:00 الرصيد: SAR 7500.00"
        assertTrue(vault.ingest("SAB",payment,at.plusSeconds(86400)))
        val loaded=vault.load()
        assertEquals(1,loaded.book!!.plans.size)
        assertEquals(220000L,loaded.book.remaining(loaded.book.plans.single(),Instant.now()))
        assertEquals(50000L,loaded.book.entries.sumOf { it.amount })
        assertEquals(750000L,loaded.book.liveBalance(Instant.now())!!.available)
        vault.change { d -> d.copy(book=d.book!!.copy(limitChanges=listOf(LimitChange("raise",Instant.now(),1200000)))) }
        assertEquals(1200000L,vault.load().book!!.limitAt(Instant.now()))
        assertEquals(950000L,vault.load().book!!.liveBalance(Instant.now())!!.available)
    }
}
