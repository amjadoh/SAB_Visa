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
    @Test fun batchImportCommitsCursorWithRecordsAndMetadataDoesNotReplayMoney() {
        vault.change { VaultData(Book(Config(1200000,0,Instant.EPOCH,"9876"))) }
        val at=Instant.parse("2026-01-20T09:00:00Z")
        val sms=Triple("SAB","شراء بطاقة Visa (9876) لدى SYNTHETIC بمبلغ SAR 42.75 الرصيد: SAR 6543.21",at)
        assertEquals(1,vault.ingestBatch(listOf(sms,sms,Triple("SAB","رمز التحقق: 1234",at)),12345,77))
        val saved=vault.load()
        assertEquals(77L,saved.smsId)
        assertEquals(12345L,saved.smsAfter)
        assertEquals(0,vault.ingestBatch(listOf(sms),12346,78))
        assertEquals(saved.book,vault.load().book)
        val book=vault.current().book
        vault.change { it.copy(updateAccess=UpdateAccess("synthetic_read_credential_12345")) }
        assertSame(book,vault.current().book)
        assertEquals(78L,vault.load().smsId)
        assertEquals(1,vault.load().inbox.size)
        vault.change { it.copy(smsAfter=0,smsId=0) }
        assertEquals(0,vault.ingestBatch(listOf(sms),12347,78))
        assertEquals(saved.book,vault.load().book)
    }
    @Test fun correctingCardReplaysStoredPlanAndKeepsUpdateConnection() {
        vault.change { VaultData(Book(Config(1200000,0,Instant.EPOCH,"1111")),updateAccess=UpdateAccess("synthetic_read_credential_12345")) }
        val plan="تم تحويل العملية الشرائية بمبلغ س.ر 2,400.00 على البطاقة الائتمانية9876 الى12 قسط شهرى, مبلغ القسط س.ر 200.00, الربح الشهرى .00%و0 رسوم ادارية."
        val at=Instant.parse("2026-01-15T09:00:00Z")
        vault.ingest("SAB",plan,at)
        assertEquals("OTHER_CARD",vault.load().inbox.single().status)
        assertTrue(vault.load().book!!.plans.isEmpty())
        vault.change { it.copy(book=it.book!!.correctSmsCard("9876")) }
        assertEquals("AUTO",vault.load().inbox.single().status)
        assertEquals(1,vault.load().book!!.plans.size)
        assertEquals("synthetic_read_credential_12345",vault.load().updateAccess.token)
        assertFalse(vault.ingest("SAB",plan,at))
        vault.change { it }
        assertEquals(1,vault.load().book!!.plans.size)
    }
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
        val payment="بطاقة ائتمانية: تأكيد السداد تم سداد بطاقتك Visa (***9876) بمبلغ SAR 500.00 في: 2026-02-03 12:00:00 الرصيد: SAR 7500.00"
        assertTrue(vault.ingest("SAB",payment,Instant.parse("2026-02-03T09:00:00Z")))
        val loaded=vault.load()
        assertEquals(1,loaded.book!!.plans.size)
        assertEquals(220000L,loaded.book.remaining(loaded.book.plans.single(),Instant.now()))
        assertEquals(50000L,loaded.book.entries.sumOf { it.amount })
        assertEquals(750000L,loaded.book.liveBalance(Instant.now())!!.available)
        assertEquals(30,loaded.book.config.closeDay)
        assertEquals(3,loaded.book.config.installmentDay)
        vault.change { d -> d.copy(book=d.book!!.copy(config=d.book.config.copy(closeDay=10))) }
        assertEquals(240000L,vault.load().book!!.remaining(vault.load().book!!.plans.single(),Instant.now()))
        vault.change { d -> d.copy(book=d.book!!.copy(config=d.book.config.copy(closeDay=30))) }
        assertEquals(220000L,vault.load().book!!.remaining(vault.load().book!!.plans.single(),Instant.now()))
        assertEquals(50000L,vault.load().book!!.entries.sumOf { it.amount })
        vault.change { d -> d.copy(book=d.book!!.copy(limitChanges=listOf(LimitChange("raise",Instant.now(),1200000)))) }
        assertEquals(1200000L,vault.load().book!!.limitAt(Instant.now()))
        assertEquals(950000L,vault.load().book!!.liveBalance(Instant.now())!!.available)
    }
    @Test fun priorTrackingProfilePersistsAndReplaysWithoutNewMoney() {
        val at=Instant.parse("2026-01-01T00:00:00Z")
        vault.change { VaultData(Book(Config(1200000,0,at,"9876"))) }
        val plan="تم تحويل العملية الشرائية بمبلغ س.ر 2,400.00 على البطاقة الائتمانية9876 الى12 قسط شهرى, مبلغ القسط س.ر 200.00, الربح الشهرى .00%و0 رسوم ادارية."
        vault.ingest("SAB",plan,Instant.parse("2026-01-15T09:00:00Z"))
        val payment="بطاقة ائتمانية: تأكيد السداد تم سداد بطاقتك Visa (***9876) بمبلغ SAR 210.00 في: 2026-02-01 12:00:00 الرصيد: SAR 3000.00"
        vault.ingest("SAB",payment,Instant.parse("2026-02-01T09:00:00Z"))
        val data=vault.load()
        val pid=data.book!!.plans.single().id
        val paymentId=data.inbox.single { it.parsed.type==SmsType.PAYMENT }.id
        val profile="""{"version":1,"closeDay":30,"installmentDay":3,"priorAllocations":[{"planId":"$pid","paymentId":"$paymentId","approvedAt":"${Instant.now()}"}]}"""
        vault.importTrackingProfile(profile)
        vault.importTrackingProfile(profile)
        val b=vault.load().book!!
        assertEquals(1,b.priorAllocations.size)
        assertEquals(21000L,b.entries.sumOf { it.amount })
        assertEquals(220000L,b.remaining(b.plans.single(),Instant.now()))
        assertEquals(300000L,b.liveBalance(Instant.now())!!.available)
        assertEquals(20000L,b.repaymentSummary(Instant.parse("2026-02-20T09:00:00Z"))!!.cyclePrincipal)
        vault.change { it }
        assertEquals(b,vault.load().book)
        val bad=profile.replace(paymentId,"missing-synthetic-payment")
        try { vault.importTrackingProfile(bad); fail("Unknown payment must fail") } catch(_: IllegalArgumentException) { }
        assertEquals(b,vault.load().book)
    }
}
