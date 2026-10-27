package com.sabvisa.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Entirely synthetic fixtures: no customer identifiers, merchants, dates or amounts copied. */
class SmsParserTest {
    private val received=Instant.parse("2026-02-05T10:30:00Z")
    private fun parse(body: String,sender: String="SAB")=SmsParser.parse(sender,body,received,setOf("SAB"))
    private fun purchase()="شراء عبر نقاط البيع\nباستخدام بطاقة الأول Visa Noon الائتمانية (9876) لدى TEST STORE بمبلغ SAR 42.75 من خلال Google Pay\nتاريخ: 13:30:00 05-02-2026\nالرصيد: SAR 6543.21"
    @Test fun arabicPurchaseExtractsAmountSeparatelyFromAvailable() {
        val p=parse(purchase()); assertEquals(SmsType.PURCHASE,p.type); assertEquals(4275L,p.amount); assertEquals(654321L,p.available)
        assertEquals("9876",p.last4); assertEquals("TEST STORE",p.merchant); assertEquals(received,p.at)
    }
    @Test fun arabicDigitsAndBidiAreNormalized() {
        val p=parse("شراء بطاقة (٩٨٧٦) بمبلغ SAR \u200f٤٢٫٧٥ تاريخ: 13:30:00 05-02-2026")
        // Arabic decimal separator is normalized before numeric matching.
        assertEquals(4275L,p.amount)
    }
    @Test fun confirmationIsAcceptedButInitiationIsIgnored() {
        assertEquals(SmsType.IGNORE,parse("بطاقة ائتمانية: تسديد بطاقة: VISA;9876*** مبلغ: SAR 120.00 من: 999*** في: 13:30:00 05-02-2026").type)
        val p=parse("بطاقة ائتمانية: تأكيد السداد عزيزي العميل، تم سداد بطاقتك Visa Noon الائتمانية (9876***) بمبلغ SAR 120.00 في: 13:30:00 05-02-2026 الرصيد: SAR 6543.21")
        assertEquals(SmsType.PAYMENT,p.type); assertEquals(12000L,p.amount); assertEquals("9876",p.last4)
    }
    @Test fun otpAndTransfersNeverBecomeCandidates() {
        assertEquals(SmsType.IGNORE,parse("رمز التحقق: 1234 للبطاقة Visa").type)
        assertEquals(SmsType.IGNORE,parse("إيداع حوالة واردة SAR 120.00").type)
        assertEquals(SmsType.IGNORE,parse("verification code 123456 Visa").type)
    }
    @Test fun cashbackWalletDoesNotBecomeCardRefund() {
        assertEquals(SmsType.IGNORE,parse("حصلت على استرداد نقدي بقيمة SAR 99.00 في محفظة نون الخاصة بك").type)
    }
    @Test fun statementHasSeparateDueDateAndNoAccountNumberField() {
        val p=parse("تم إصدار كشف حساب بطاقة Visa Noon الائتمانية (9876) رقم الحساب: 0000000000 المبلغ المستحق: SAR 321.09 تاريخ الاستحقاق: 26-02-2026 23:59:59")
        assertEquals(SmsType.STATEMENT,p.type); assertEquals(32109L,p.amount); assertEquals("2026-02-26",p.due.toString()); assertEquals(received,p.at)
    }
    @Test fun balanceReminderIsNotDeclinedPurchase() {
        val p=parse("الرصيد المتوفر في بطاقتك الأول VISA الائتمانية هو 900 ريال سعودي وذلك لتنبيهك من أية عمليات مرفوضة")
        assertEquals(SmsType.BALANCE,p.type); assertEquals(90000L,p.available)
    }
    @Test fun unknownAndForeignAndDeclinedStayInReview() {
        assertEquals(SmsType.REVIEW,parse("شراء بطاقة Visa بمبلغ USD 25.00").type)
        assertEquals(SmsType.REVIEW,parse("شراء بطاقة Visa بمبلغ SAR 25.00 USD 7.00").type)
        assertEquals(SmsType.REVIEW,parse("شراء بطاقة Visa مرفوض SAR 12.00").type)
        assertEquals(SmsType.REVIEW,parse("عملية بطاقة غير معروفة").type)
    }
    @Test fun senderMatchingIsExact() {
        assertEquals(SmsType.IGNORE,parse(purchase(),"FAKE-SAB").type)
        assertEquals(SmsType.PURCHASE,parse(purchase(),"sab").type)
    }
    @Test fun sameAmountLegitimatePurchasesHaveDistinctFingerprints() {
        val a=fingerprint("SAB",purchase(),received)
        assertEquals(a,fingerprint("SAB",purchase(),received))
        assertNotEquals(a,fingerprint("SAB",purchase(),received.plusSeconds(1)))
    }
    @Test fun cardRefundIsSeparateFromPayment() {
        assertEquals(SmsType.REFUND,parse("استرجاع عملية بطاقة Visa (9876) بمبلغ SAR 42.75").type)
    }
    @Test fun invalidDateAndOverPreciseAmountAreNotPosted() {
        assertEquals(SmsType.REVIEW,parse("شراء بطاقة Visa (9876) بمبلغ SAR 42.75 تاريخ: 13:30:00 31-02-2026").type)
        assertEquals(SmsType.REVIEW,parse("شراء بطاقة Visa (9876) بمبلغ SAR 42.759").type)
    }
    @Test fun prefixMaskedCardAndIsoTimestampAndStatementDue() {
        val p=parse("بطاقة ائتمانية: تأكيد السداد تم سداد بطاقتك Visa Noon الائتمانية (***9876) بمبلغ SAR 123.45 في: 2026-02-05 13:30:00 الرصيد: SAR 4321.09")
        assertEquals(SmsType.PAYMENT,p.type); assertEquals("9876",p.last4); assertEquals(received,p.at)
        val s=parse("تم إصدار كشف حساب بطاقة Visa (9876) المبلغ المستحق: SAR 234.56 تاريخ الاستحقاق: 2026-02-26 23:59:59")
        assertEquals("2026-02-26",s.due.toString())
        assertEquals(12345L,parse("شراء عبر الإنترنت باستخدام بطاقة Visa (9876) بمبلغ 123.45 SAR لدى SYNTHETIC تاريخ: 2026-02-05 13:30:00 الرصيد: SAR 4321.09").amount)
    }
    @Test fun accountDepositsAreExcludedEvenIfBodyMentionsCard() {
        listOf("إيداع","ايداع","deposit","account credited").forEach { word ->
            assertEquals(SmsType.IGNORE,parse("$word في الحساب SAR 123.45 بطاقة Visa (9876)").type)
        }
    }
    @Test fun installmentConfirmationCreatesPlanButPromotionDoesNot() {
        val text="تم تحويل العملية الشرائية بمبلغ س.ر 2,400.00 على البطاقة الائتمانية9876 الى12 قسط شهرى, مبلغ القسط س.ر 200.00, الربح الشهرى .00%و0 رسوم ادارية. التاريخ والوقت: 05-02-26 13:30:00"
        val p=parse(text)
        assertEquals(SmsType.PLAN,p.type); assertEquals("9876",p.last4)
        assertEquals(240000L,p.amount); assertEquals(12,p.months); assertEquals(20000L,p.monthly)
        val later=SmsParser.parse("SAB",text,received.plusSeconds(60),setOf("SAB"))
        assertEquals(messageFingerprint("SAB",text,p),messageFingerprint("SAB",text,later))
        assertEquals(SmsType.REVIEW,parse(text.replace(".00%","1.00%")).type)
        assertEquals(SmsType.REVIEW,parse(text.replace("و0 رسوم","و100 رسوم")).type)
        assertEquals(SmsType.IGNORE,parse("حوّل عمليتك الشرائية على بطاقة Visa إلى أقساط شهرية. للإستفادة قم بالدخول للتطبيق.").type)
    }
}
