package com.sabvisa.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AutomaticSmsTest {
    private val start=Instant.parse("2026-01-01T00:00:00Z")
    private val now=Instant.parse("2026-05-30T20:00:00Z")
    private fun at(date: String)=LocalDate.parse(date).atTime(12,0).atZone(RIYADH).toInstant()
    private fun book()=Book(Config(1_200_000,0,start,"9876",closeDay=10,installmentDay=11))
    private fun plan(id: String="p",principal: Long=900_000,months: Int=12)=Plan(id,"Synthetic",principal,months,LocalDate.of(2026,1,11),start)
    private fun sms(id: String,type: SmsType,amount: Long?,date: String,balance: Long?=null)=SmsRecord(id,ParsedSms(type,amount,balance,"9876",at(date),reason="synthetic"),at(date).plusSeconds(3))
    @Test fun overpaymentKeepsSignedSurplusAndDoesNotPayFutureInstallments() {
        val p=plan(principal=240_000)
        val b=book().copy(plans=listOf(p),checkpoints=listOf(Checkpoint("opening",at("2026-02-01"),900_000,"synthetic bank")))
        assertEquals(80_000L,b.repaymentSummary(at("2026-02-12"))!!.targetBeforeClose)
        val paid=applyAutomaticSms(b,listOf(sms("overpay",SmsType.PAYMENT,100_000,"2026-02-12",1_000_000)),now)
        val summary=paid.book.repaymentSummary(at("2026-02-12"))!!
        assertEquals(-20_000L,summary.ordinaryNet)
        assertEquals(-20_000L,summary.netBeforeClose)
        assertEquals(0L,summary.targetBeforeClose)
        assertEquals(20_000L,summary.surplusBeforeClose)
        assertEquals(100_000L,paid.book.entries.sumOf { it.amount })
        assertEquals(220_000L,paid.book.remaining(p,now))
        assertEquals(1,paid.book.completedInstallments(p,now))
        val next=paid.book.repaymentSummary(at("2026-03-12"))!!
        assertEquals(20_000L,next.cyclePrincipal)
        assertEquals(0L,next.netBeforeClose)
        assertEquals(0L,next.targetBeforeClose)
        assertEquals(0L,next.surplusBeforeClose)
        assertEquals(paid,applyAutomaticSms(paid.book,paid.records,now))
    }
    @Test fun surplusOffsetsUnpaidCycleBeforeClampingPayableAmount() {
        val p=plan(principal=240_000)
        fun summary(available: Long, date: String="2026-02-12")=book().copy(plans=listOf(p),checkpoints=listOf(Checkpoint("balance",at("2026-02-01"),available,"synthetic bank"))).repaymentSummary(at(date))!!
        val extra=summary(1_000_000)
        assertEquals(-40_000L,extra.ordinaryNet)
        assertEquals(20_000L,extra.cyclePrincipal)
        assertEquals(-20_000L,extra.netBeforeClose)
        assertEquals(0L,extra.paymentToClearPurchases)
        assertEquals(0L,extra.targetBeforeClose)
        assertEquals(20_000L,extra.surplusBeforeClose)
        val exact=summary(980_000)
        assertEquals(0L,exact.netBeforeClose)
        assertEquals(0L,exact.targetBeforeClose)
        assertEquals(0L,exact.surplusBeforeClose)
        val partial=summary(970_000)
        assertEquals(10_000L,partial.targetBeforeClose)
        assertEquals(10_000L,partial.paymentToClearPurchases)
        assertEquals(0L,partial.surplusBeforeClose)
        val beforeDay=book().copy(config=book().config.copy(closeDay=30,installmentDay=3),plans=listOf(p),
            checkpoints=listOf(Checkpoint("before-day",at("2026-01-31"),1_000_000,"synthetic bank"))).repaymentSummary(at("2026-02-01"))!!
        assertEquals(0L,beforeDay.principalInNextPayment)
        assertEquals(-20_000L,beforeDay.netBeforeClose)
        assertEquals(0L,beforeDay.targetBeforeClose)
    }
    @Test fun laterPurchasesConsumeCreditWithoutDuplicateCashOrCalendarEntries() {
        val b=book().copy(checkpoints=listOf(Checkpoint("credit",at("2026-02-01"),1_250_000,"synthetic bank")))
        assertEquals(-50_000L,b.repaymentSummary(at("2026-02-02"))!!.netBeforeClose)
        val first=applyAutomaticSms(b,listOf(sms("buy-one",SmsType.PURCHASE,20_000,"2026-02-03")),now)
        assertEquals(30_000L,first.book.repaymentSummary(at("2026-02-04"))!!.surplusBeforeClose)
        assertEquals(0L,first.book.repaymentSummary(at("2026-02-04"))!!.targetBeforeClose)
        val second=applyAutomaticSms(first.book,first.records+sms("buy-two",SmsType.PURCHASE,40_000,"2026-02-05"),now)
        assertEquals(10_000L,second.book.repaymentSummary(at("2026-02-06"))!!.targetBeforeClose)
        assertEquals(0L,second.book.repaymentSummary(at("2026-02-06"))!!.surplusBeforeClose)
        assertEquals(60_000L,second.book.entries.sumOf { it.amount })
        assertEquals(second,applyAutomaticSms(second.book,second.records,now))
    }
    @Test fun correctingSmsCardRecoversPlanAndPaymentsWithoutDuplicateCash() {
        val wrong=Book(Config(1_200_000,0,Instant.EPOCH,"1111",closeDay=30,installmentDay=3))
        val plan=sms("plan",SmsType.PLAN,240_000,"2026-01-15").let { it.copy(parsed=it.parsed.copy(months=12,monthly=20_000)) }
        val records=listOf(plan,sms("buy",SmsType.PURCHASE,10_000,"2026-02-01",300_000),sms("pay",SmsType.PAYMENT,25_000,"2026-02-03",325_000))
        val excluded=applyAutomaticSms(wrong,records,now)
        assertTrue(excluded.records.all { it.status=="OTHER_CARD" })
        assertTrue(excluded.book.plans.isEmpty())
        val recovered=applyAutomaticSms(excluded.book.correctSmsCard("٩٨٧٦"),excluded.records,now)
        assertTrue(recovered.records.all { it.status=="AUTO" })
        assertEquals(records.map { it.id },recovered.records.map { it.id })
        assertEquals(1,recovered.book.plans.size)
        assertEquals(220_000L,recovered.book.remaining(recovered.book.plans.single(),now))
        assertEquals(25_000L,recovered.book.entries.filter { it.kind==Kind.PAYMENT }.sumOf { it.amount })
        assertEquals(325_000L,recovered.book.liveBalance(now)!!.available)
        assertEquals(recovered,applyAutomaticSms(recovered.book,recovered.records,now))
    }
    @Test fun correctionDoesNotMergeAnotherCardOrManualHistory() {
        val b=Book(Config(1_200_000,0,Instant.EPOCH,"1111"))
        val records=listOf(sms("match",SmsType.PURCHASE,100,"2026-01-12",500),sms("other",SmsType.PURCHASE,200,"2026-01-13",400).let { it.copy(parsed=it.parsed.copy(last4="2222")) })
        val fixed=applyAutomaticSms(b.correctSmsCard("9876"),records,now)
        assertEquals(100L,fixed.book.entries.sumOf { it.amount })
        assertEquals("OTHER_CARD",fixed.records[1].status)
        assertThrows(IllegalArgumentException::class.java) { b.copy(config=b.config.copy(openingOrdinary=100)).correctSmsCard("9876") }
        assertThrows(IllegalArgumentException::class.java) { b.copy(plans=listOf(plan())).correctSmsCard("9876") }
        assertThrows(IllegalArgumentException::class.java) { b.correctSmsCard("123") }
    }
    @Test fun postsAndUsesAbsoluteBankBalanceWithoutDoubleAddingPayment() {
        val records=listOf(sms("buy",SmsType.PURCHASE,10_000,"2026-01-12",300_000),sms("pay",SmsType.PAYMENT,75_000,"2026-01-13",375_000))
        val b=applyAutomaticSms(book().copy(plans=listOf(plan())),records,now).book
        assertEquals(825_000,b.remaining(b.plans.single(),now)); assertEquals(375_000,b.liveBalance(now)!!.available)
        assertEquals(75_000,b.entries.filter { it.kind==Kind.PAYMENT }.sumOf { it.amount })
        assertFalse(b.liveBalance(now)!!.estimated)
    }
    @Test fun afterConfiguredCloseCappedOncePerCycleAndRemainingIsExtra() {
        val records=listOf(sms("a",SmsType.PAYMENT,30_000,"2026-01-11"),sms("b",SmsType.PAYMENT,100_000,"2026-01-20"),sms("c",SmsType.PAYMENT,5_000,"2026-01-28"))
        val b=applyAutomaticSms(book().copy(plans=listOf(plan())),records,now).book
        assertEquals(75_000,b.entries.sumOf { it.principalPaid }); assertEquals(135_000,b.entries.sumOf { it.amount })
        assertEquals(60_000,b.entries.filter { it.planId==null }.sumOf { it.amount })
    }
    @Test fun closingDayIsIncludedAndNextCycleResetsCap() {
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
        val payment=sms("pay",SmsType.PAYMENT,50_000,"2026-02-13",350_000)
        val first=applyAutomaticSms(book(),listOf(payment,purchase,created),now)
        val second=applyAutomaticSms(first.book,first.records,now)
        assertEquals(first,second); assertEquals(1,second.book.plans.size)
        assertEquals(220_000,second.book.remaining(second.book.plans.single(),now))
        assertEquals(240_000,second.book.entries.filter { it.kind==Kind.PURCHASE }.sumOf { it.amount })
        assertEquals(50_000,second.book.entries.filter { it.kind==Kind.PAYMENT }.sumOf { it.amount })
        assertEquals(350_000,second.book.liveBalance(now)!!.available)
    }
    @Test fun repaymentIncludesMonthlyPrincipalOnlyWhenNextPaymentWouldAllocateIt() {
        val p=plan().copy(paidBeforeTracking=1,firstDue=LocalDate.of(2026,2,11))
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
    @Test fun endOfMonthAllocationWaitsUntilThirdAndIgnoresLateNotification() {
        val b=book().copy(config=book().config.copy(closeDay=30,installmentDay=3))
        val plan=sms("plan",SmsType.PLAN,240_000,"2026-01-15").let { it.copy(parsed=it.parsed.copy(months=12,monthly=20_000)) }
        val before=sms("before",SmsType.PAYMENT,10_000,"2026-01-30",300_000)
        val early=sms("early",SmsType.PAYMENT,5_000,"2026-02-01",305_000)
        val after=sms("after",SmsType.PAYMENT,25_000,"2026-02-03",330_000)
        val notice=sms("notice",SmsType.STATEMENT,20_100,"2026-02-05").let { it.copy(parsed=it.parsed.copy(due=LocalDate.of(2026,2,25))) }
        val more=sms("extra",SmsType.PAYMENT,25_000,"2026-02-15",355_000)
        val first=applyAutomaticSms(b,listOf(more,notice,after,early,before,plan),now)
        val replay=applyAutomaticSms(first.book,first.records,now)
        val p=replay.book.plans.single()
        assertEquals(first,replay)
        assertEquals(0L,replay.book.entries.filter { it.sourceId?.startsWith("auto:before:")==true }.sumOf { it.principalPaid })
        assertEquals(0L,replay.book.entries.filter { it.sourceId?.startsWith("auto:early:")==true }.sumOf { it.principalPaid })
        assertEquals(20_000L,replay.book.entries.filter { it.sourceId?.startsWith("auto:after:")==true }.sumOf { it.principalPaid })
        assertEquals(20_000L,replay.book.entries.sumOf { it.principalPaid })
        assertEquals(220_000L,replay.book.remaining(p,now))
        assertEquals(355_000L,replay.book.liveBalance(now)!!.available)
        assertEquals(1,replay.book.completedInstallments(p,now))
        assertEquals(0L,replay.book.repaymentSummary(at("2026-02-15"))!!.principalInNextPayment)
        assertEquals(0L,replay.book.repaymentSummary(at("2026-03-01"))!!.principalInNextPayment)
        val goal=replay.book.repaymentSummary(at("2026-03-01"))!!
        assertEquals(20_000L,goal.cyclePrincipal)
        assertEquals(goal.purchasesToClear+20_000,goal.targetBeforeClose)
        assertEquals(20_000L,replay.book.repaymentSummary(at("2026-03-03"))!!.principalInNextPayment)
        assertEquals(LocalDate.of(2026,2,3),b.installmentDate(at("2026-01-31")))
    }
    @Test fun priorInstallmentUsesExistingCashAndDoesNotCoverCurrentCycle() {
        val initial=book().copy(config=book().config.copy(closeDay=30,installmentDay=3))
        val plan=sms("plan",SmsType.PLAN,240_000,"2026-01-15").let { it.copy(parsed=it.parsed.copy(months=12,monthly=20_000)) }
        val old=sms("old",SmsType.PAYMENT,21_000,"2026-02-01",300_000)
        val buy=sms("buy",SmsType.PURCHASE,200_000,"2026-02-20",100_000)
        val before=applyAutomaticSms(initial,listOf(plan,old,buy),now)
        val p=before.book.plans.single()
        val prior=PriorAllocation(p.id,old.id,now)
        val first=applyAutomaticSms(before.book.copy(priorAllocations=listOf(prior)),before.records,now)
        val replay=applyAutomaticSms(first.book,first.records.reversed(),now)
        val b=replay.book
        assertEquals(21_000L,b.entries.filter { it.kind==Kind.PAYMENT }.sumOf { it.amount })
        assertEquals(20_000L,b.entries.filter { it.sourceId?.contains(":prior:")==true }.sumOf { it.principalPaid })
        assertEquals(1_000L,b.entries.filter { it.kind==Kind.PAYMENT && it.planId==null }.sumOf { it.amount })
        assertEquals(before.book.balances(now).debt,b.balances(now).debt)
        assertEquals(100_000L,b.liveBalance(now)!!.available)
        assertEquals(220_000L,b.remaining(p,now))
        assertEquals(1,b.completedInstallments(p,now))
        assertEquals(0L,b.principalPaidIn(p,b.billingMonth(at("2026-02-20"))))
        val target=b.repaymentSummary(at("2026-02-20"))!!
        assertEquals(880_000L,target.purchasesToClear)
        assertEquals(20_000L,target.cyclePrincipal)
        assertEquals(900_000L,target.targetBeforeClose)
        val next=sms("current",SmsType.PAYMENT,target.targetBeforeClose,"2026-02-25",1_000_000)
        val paid=applyAutomaticSms(b,replay.records+next,now).book
        assertEquals(2,paid.completedInstallments(p,now))
        assertEquals(0L,paid.repaymentSummary(at("2026-02-25"))!!.targetBeforeClose)
        assertEquals(921_000L,paid.entries.filter { it.kind==Kind.PAYMENT }.sumOf { it.amount })
        val removed=applyAutomaticSms(b.copy(priorAllocations=emptyList()),replay.records,now).book
        assertEquals(before.book.entries,removed.entries)
    }
    @Test fun priorInstallmentCannotInventPaymentOrExceedItsAmount() {
        val b=book().copy(plans=listOf(plan()))
        val short=sms("short",SmsType.PAYMENT,1_000,"2026-01-12",300_000)
        assertThrows(IllegalArgumentException::class.java) {
            applyAutomaticSms(b.copy(priorAllocations=listOf(PriorAllocation("p","short",now))),listOf(short),now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            applyAutomaticSms(b.copy(priorAllocations=listOf(PriorAllocation("p","missing",now))),emptyList(),now)
        }
    }
    @Test fun priorAndCurrentInstallmentsKeepFractionalPrincipalExact() {
        val p=plan(principal=240_001).copy(firstDue=LocalDate.of(2026,1,10))
        val prior=sms("old",SmsType.PAYMENT,25_000,"2026-01-11",300_000)
        val next=sms("next",SmsType.PAYMENT,25_000,"2026-01-20",325_000)
        val b=applyAutomaticSms(book().copy(plans=listOf(p),priorAllocations=listOf(PriorAllocation(p.id,prior.id,now))),listOf(prior,next),now).book
        assertEquals(40_001L,b.entries.sumOf { it.principalPaid })
        assertEquals(50_000L,b.entries.sumOf { it.amount })
        assertEquals(200_000L,b.remaining(p,now))
    }
    @Test fun issuedCardFeeIncludesVatOnceAndUsesLaterBankAnchor() {
        val at=at("2026-01-15")
        val text="عزيزي العميل, تم خصم مبلغ Annual Fee + VAT من بطاقة Visa الائتمانية (9876). المبلغ: 115.00 ريال سعودي"
        val parsed=SmsParser.parse("SAB",text,at,setOf("SAB"))
        assertEquals(SmsType.FEE,parsed.type)
        val record=SmsRecord("fee",parsed,at)
        val pay=sms("pay",SmsType.PAYMENT,10_000,"2026-01-16",300_000)
        val result=applyAutomaticSms(book(),listOf(record,pay),now)
        assertEquals(11_500L,result.book.entries.single { it.kind==Kind.FEE }.amount)
        assertEquals(300_000L,result.book.liveBalance(now)!!.available)
        assertEquals(result,applyAutomaticSms(result.book,result.records,now))
    }
}
