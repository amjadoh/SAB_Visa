package com.sabvisa.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AutomaticSmsTest {
    private val start=Instant.parse("2026-01-01T00:00:00Z")
    private val now=Instant.parse("2026-05-30T20:00:00Z")
    private fun at(date: String)=LocalDate.parse(date).atTime(12,0).atZone(RIYADH).toInstant()
    private fun book()=Book(Config(1_200_000,0,start,"9876"))
    private fun plan(id: String="p",principal: Long=900_000,months: Int=12)=Plan(id,"Synthetic",principal,months,LocalDate.of(2026,1,11),start)
    private fun sms(id: String,type: SmsType,amount: Long?,date: String,balance: Long?=null)=SmsRecord(id,ParsedSms(type,amount,balance,"9876",at(date),reason="synthetic"),at(date).plusSeconds(3))
    @Test fun postsAndUsesAbsoluteBankBalanceWithoutDoubleAddingPayment() {
        val records=listOf(sms("buy",SmsType.PURCHASE,10_000,"2026-01-12",300_000),sms("pay",SmsType.PAYMENT,75_000,"2026-01-13",375_000))
        val b=applyAutomaticSms(book().copy(plans=listOf(plan())),records,now).book
        assertEquals(825_000,b.remaining(b.plans.single(),now)); assertEquals(375_000,b.liveBalance(now)!!.available)
        assertEquals(75_000,b.entries.filter { it.kind==Kind.PAYMENT }.sumOf { it.amount })
        assertFalse(b.liveBalance(now)!!.estimated)
    }
    @Test fun afterTenCappedOncePerMonthAndRemainingIsExtra() {
        val records=listOf(sms("a",SmsType.PAYMENT,30_000,"2026-01-11"),sms("b",SmsType.PAYMENT,100_000,"2026-01-20"),sms("c",SmsType.PAYMENT,5_000,"2026-01-28"))
        val b=applyAutomaticSms(book().copy(plans=listOf(plan())),records,now).book
        assertEquals(75_000,b.entries.sumOf { it.principalPaid }); assertEquals(135_000,b.entries.sumOf { it.amount })
        assertEquals(60_000,b.entries.filter { it.planId==null }.sumOf { it.amount })
    }
    @Test fun daysOneThroughTenAreAdditionalAndNextMonthResetsCap() {
        val records=listOf(sms("a",SmsType.PAYMENT,75_000,"2026-01-10"),sms("b",SmsType.PAYMENT,75_000,"2026-01-11"),sms("c",SmsType.PAYMENT,75_000,"2026-02-11"))
        val b=applyAutomaticSms(book().copy(plans=listOf(plan())),records,now).book
        assertEquals(150_000,b.entries.sumOf { it.principalPaid }); assertEquals(750_000,b.remaining(b.plans.single(),now))
    }
    @Test fun sameSmsAndSemanticRepeatDoNotDuplicateAndReplayIsStable() {
        val record=sms("a",SmsType.PAYMENT,75_000,"2026-01-11",300_000)
        val first=applyAutomaticSms(book().copy(plans=listOf(plan())),listOf(record,record.copy(id="b",received=record.received.plusSeconds(10))),now)
        val second=applyAutomaticSms(first.book,first.records,now)
        assertEquals(first,second); assertEquals(75_000,first.book.entries.sumOf { it.amount }); assertEquals("DUPLICATE",first.records[1].status)
    }
    @Test fun outOfOrderImportUsesTransactionTime() {
        val a=sms("a",SmsType.PAYMENT,75_000,"2026-01-12",300_000)
        val b=sms("b",SmsType.PAYMENT,75_000,"2026-01-13",375_000)
        val late=applyAutomaticSms(book().copy(plans=listOf(plan())),listOf(b),now)
        val result=applyAutomaticSms(late.book,late.records+a,now)
        assertEquals(375_000,result.book.liveBalance(now)!!.available)
        assertEquals(75_000,result.book.entries.sumOf { it.principalPaid })
        assertEquals(at("2026-01-12"),result.book.entries.first { it.principalPaid>0 }.at)
    }
    @Test fun missingCardAndOtherCardAndFutureNeverPost() {
        val a=sms("a",SmsType.PURCHASE,100,"2026-01-12",500)
        val input=listOf(a.copy(parsed=a.parsed.copy(last4=null)),a.copy(id="b",parsed=a.parsed.copy(last4="1111")),a.copy(id="c",parsed=a.parsed.copy(at=now.plusSeconds(10))))
        val result=applyAutomaticSms(book(),input,now)
        assertTrue(result.book.entries.isEmpty()); assertNull(result.book.liveBalance(now))
        assertTrue(applyAutomaticSms(result.book,result.records,now.plusSeconds(100)).book.entries.isEmpty())
    }
    @Test fun multiplePlansSplitOnePaymentWithoutIncreasingCashAmount() {
        val b=applyAutomaticSms(book().copy(plans=listOf(plan("a",120_000,12),plan("b",240_000,12))),listOf(sms("pay",SmsType.PAYMENT,50_000,"2026-01-11")),now).book
        assertEquals(50_000,b.entries.sumOf { it.amount }); assertEquals(30_000,b.entries.sumOf { it.principalPaid })
        assertEquals(3,b.entries.size)
    }
    @Test fun knownPreviouslyPaidInstallmentIsNotRecountedFromHistory() {
        val p=plan().copy(createdAt=at("2026-02-20"),firstDue=LocalDate.of(2026,2,11),paidBeforeTracking=1)
        val b=applyAutomaticSms(book().copy(plans=listOf(p)),listOf(sms("old",SmsType.PAYMENT,75_000,"2026-01-20"),sms("new",SmsType.PAYMENT,75_000,"2026-03-15")),now).book
        assertEquals(750_000,b.remaining(p,now)); assertEquals(75_000,b.entries.sumOf { it.principalPaid })
    }
    @Test fun changingLimitIsDatedAndBankAnchorIsNotOverwritten() {
        val anchor=Checkpoint("a",at("2026-01-12"),300_000,"bank")
        val b=book().copy(checkpoints=listOf(anchor),limitChanges=listOf(LimitChange("raise",at("2026-02-01"),1_500_000)))
        b.validate(); assertEquals(1_200_000,b.limitAt(at("2026-01-15"))); assertEquals(1_500_000,b.limitAt(now))
        assertEquals(600_000,b.liveBalance(now)!!.available); assertEquals(300_000,b.liveBalance(at("2026-01-15"))!!.available)
        assertTrue(b.liveBalance(now)!!.estimated); assertEquals(300_000,b.checkpoints.single().bankAvailable)
    }
    @Test fun purchaseWithoutNewBalanceIsEstimatedFromAnchor() {
        val b=applyAutomaticSms(book().copy(checkpoints=listOf(Checkpoint("a",start.plusSeconds(1),300_000,"bank"))),listOf(sms("buy",SmsType.PURCHASE,10_000,"2026-01-12")),now).book
        assertEquals(290_000,b.liveBalance(now)!!.available); assertTrue(b.liveBalance(now)!!.estimated)
    }
    @Test fun addingPlanDoesNotSpendCreditTwice() {
        val b=book().copy(checkpoints=listOf(Checkpoint("a",start,300_000,"bank")))
        assertEquals(b.liveBalance(now)!!.available,b.copy(plans=listOf(plan())).liveBalance(now)!!.available)
    }
    @Test fun legacyApprovedEntriesAndIgnoredMessagesArePreserved() {
        val record=sms("old",SmsType.PURCHASE,10_000,"2026-01-12")
        val entry=Entry("legacy",Kind.PURCHASE,10_000,record.parsed.at,"legacy",sourceId="old")
        val result=applyAutomaticSms(book().copy(entries=listOf(entry)),listOf(record.copy(status="ACCEPTED"),record.copy(id="ignored",status="IGNORED")),now)
        assertEquals(listOf(entry),result.book.entries); assertEquals("LEGACY",result.records[0].status)
    }
    @Test fun planFromSmsDoesNotDuplicatePurchaseAndPaymentsAreAllocatedAutomatically() {
        val created=sms("plan",SmsType.PLAN,240_000,"2026-01-12").let { it.copy(parsed=it.parsed.copy(months=12,monthly=20_000)) }
        val purchase=sms("purchase",SmsType.PURCHASE,240_000,"2026-01-11",300_000)
        val payment=sms("pay",SmsType.PAYMENT,50_000,"2026-01-13",350_000)
        val first=applyAutomaticSms(book(),listOf(payment,purchase,created),now)
        val second=applyAutomaticSms(first.book,first.records,now)
        assertEquals(first,second); assertEquals(1,second.book.plans.size)
        assertEquals(220_000,second.book.remaining(second.book.plans.single(),now))
        assertEquals(240_000,second.book.entries.filter { it.kind==Kind.PURCHASE }.sumOf { it.amount })
        assertEquals(50_000,second.book.entries.filter { it.kind==Kind.PAYMENT }.sumOf { it.amount })
        assertEquals(350_000,second.book.liveBalance(now)!!.available)
    }
    @Test fun repaymentIncludesMonthlyPrincipalOnlyWhenNextPaymentWouldAllocateIt() {
        val p=plan().copy(paidBeforeTracking=1)
        val b=book().copy(plans=listOf(p),checkpoints=listOf(Checkpoint("a",at("2026-02-01"),100_000,"bank")))
        val before=b.repaymentSummary(at("2026-02-10"))!!
        assertEquals(375_000,before.purchaseCapacity); assertEquals(275_000,before.purchasesToClear)
        assertEquals(0,before.principalInNextPayment); assertEquals(1,b.completedInstallments(p,now))
        val after=b.repaymentSummary(at("2026-02-11"))!!
        assertEquals(350_000,after.paymentToClearPurchases)
        val paid=applyAutomaticSms(b,listOf(sms("payment",SmsType.PAYMENT,after.paymentToClearPurchases,"2026-02-11",450_000)),now).book
        assertEquals(0,paid.repaymentSummary(at("2026-02-11"))!!.purchasesToClear)
        assertEquals(2,paid.completedInstallments(p,now))
    }
    @Test fun lateCombinedPaymentAllocatesOnlyOneMonthlyInstallment() {
        val first=sms("late",SmsType.PAYMENT,100_000,"2026-02-12",300_000)
        val second=sms("extra",SmsType.PAYMENT,10_000,"2026-02-13",310_000)
        val b=applyAutomaticSms(book().copy(plans=listOf(plan())),listOf(first,second),now).book
        assertEquals(75_000,b.entries.sumOf { it.principalPaid }); assertEquals(110_000,b.entries.sumOf { it.amount })
        assertEquals(310_000,b.liveBalance(now)!!.available)
    }
}
