package com.sabvisa.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class LedgerTest {
    private val opening=Instant.parse("2026-01-01T00:00:00Z")
    private val later=opening.plusSeconds(86400)
    private fun config(ordinary: Long=0)=Config(1_000_000,ordinary,opening,"9876")
    private fun plan()=Plan("plan","خطة تجريبية",700_000,14,LocalDate.of(2026,1,25),opening)
    private fun entry(kind: Kind, amount: Long, principal: Long=0, at: Instant=later)=
        Entry(id(),kind,amount,at,"اختبار اصطناعي",planId=if(principal>0) "plan" else null,principalPaid=principal)
    private fun invalid(action: () -> Unit) { try { action(); fail("Expected validation failure") } catch(_: IllegalArgumentException) { } }

    @Test fun openingReservesEntireRemainingPrincipal() {
        val b=Book(config(),listOf(plan())); b.validate()
        assertEquals(300_000,b.balances(later).available)
        assertEquals(700_000,b.balances(later.plusSeconds(86400*100)).principal)
    }
    @Test fun monthlyPaymentReleasesOnlyAllocatedPrincipal() {
        val b=Book(config(10_000),listOf(plan()),listOf(entry(Kind.PAYMENT,60_000,50_000)))
        b.validate(); assertEquals(350_000,b.balances(later).available)
        assertEquals(650_000,b.balances(later).principal); assertEquals(0,b.balances(later).ordinary)
    }
    @Test fun partialAndAdvancePaymentsRemainExact() {
        val p=plan()
        val b=Book(config(),listOf(p),listOf(entry(Kind.PAYMENT,25_000,25_000),entry(Kind.PAYMENT,75_000,75_000)))
        b.validate(); assertEquals(600_000,b.remaining(p,later)); assertEquals(0,b.duePrincipal(p,LocalDate.of(2026,2,25)))
        assertEquals(50_000,b.duePrincipal(p,LocalDate.of(2026,3,25)))
    }
    @Test fun unallocatedPaymentDoesNotAutomaticallyPayPlan() {
        val p=plan(); val b=Book(config(30_000),listOf(p),listOf(entry(Kind.PAYMENT,50_000)))
        assertEquals(700_000,b.remaining(p,later)); assertEquals(-20_000,b.balances(later).ordinary)
    }
    @Test fun conversionIsBalanceNeutral() {
        val before=Book(config(700_000))
        val converted=Book(config(700_000),listOf(plan().copy(createdAt=later)),listOf(entry(Kind.ADJUSTMENT,-700_000)))
        converted.validate(); assertEquals(before.balances(later).available,converted.balances(later).available)
        assertEquals(before.balances(opening),converted.balances(opening))
    }
    @Test fun newInstallmentPurchaseCountsOnceInBudgetAndDebt() {
        val p=plan().copy(createdAt=later)
        val b=Book(config(),listOf(p),listOf(entry(Kind.PURCHASE,p.principal),entry(Kind.ADJUSTMENT,-p.principal)))
        b.validate(); assertEquals(700_000,b.balances(later).debt)
        assertEquals(700_000,b.spending(opening,later.plusSeconds(1)))
    }
    @Test fun fractionalInstallmentsSumToPrincipal() {
        for(principal in listOf(10_001L,700_001L,100_000L)) for(months in 1..120) {
            val p=Plan("p","test",principal,months,LocalDate.of(2026,1,31),opening)
            assertEquals(principal,(0 until months).sumOf { p.installment(it) })
            assertTrue((0 until months).all { p.installment(it)>0 })
        }
    }
    @Test fun endOfMonthDueDoesNotDrift() {
        val p=Plan("p","test",900,3,LocalDate.of(2026,1,31),opening)
        assertEquals(LocalDate.of(2026,2,28),p.due(1)); assertEquals(LocalDate.of(2026,3,31),p.due(2))
    }
    @Test fun purchasesRefundsFeesAndCashHaveSeparateEffects() {
        val b=Book(config(),entries=listOf(entry(Kind.PURCHASE,20_000),entry(Kind.REFUND,5_000),entry(Kind.FEE,1_000),entry(Kind.CASH,3_000)))
        b.validate(); assertEquals(19_000,b.balances(later).debt); assertEquals(15_000,b.spending(opening,later.plusSeconds(1)))
    }
    @Test fun overLimitAndCreditBalanceAreNotClamped() {
        assertEquals(-20_000,Book(config(1_020_000)).balances(later).available)
        assertEquals(1_010_000,Book(config(),entries=listOf(entry(Kind.PAYMENT,10_000))).balances(later).available)
    }
    @Test fun duplicateSourceRejectedEvenWhenVoided() {
        val e=entry(Kind.PURCHASE,100).copy(sourceId="sms")
        invalid { Book(config(),entries=listOf(e.copy(voided=true),e.copy(id=id()))).validate() }
    }
    @Test fun openingAndEarlierEventsRejected() {
        invalid { Book(config(),entries=listOf(entry(Kind.PURCHASE,100,at=opening))).validate() }
    }
    @Test fun overAllocationAndMissingPlansRejected() {
        invalid { Book(config(),listOf(plan()),listOf(entry(Kind.PAYMENT,800_000,800_000))).validate() }
        invalid { Book(config(),entries=listOf(entry(Kind.PAYMENT,100,100))).validate() }
        invalid { Entry(id(),Kind.PURCHASE,100,later,"test",planId="p",principalPaid=10) }
    }
    @Test fun voidRestoresOriginalBalancesAndKeepsHistory() {
        val e=entry(Kind.PAYMENT,50_000,50_000)
        val b=Book(config(),listOf(plan()),listOf(e.copy(voided=true)))
        b.validate(); assertEquals(300_000,b.balances(later).available); assertEquals(1,b.entries.size)
    }
    @Test fun snapshotUsesTransactionTimeNotListOrderOrFuture() {
        val first=entry(Kind.PURCHASE,100)
        val future=entry(Kind.PURCHASE,900,at=later.plusSeconds(100))
        val b=Book(config(),entries=listOf(future,first))
        assertEquals(100,b.balances(later).debt)
    }
    @Test fun checkpointComparisonDoesNotChangeLedger() {
        val b=Book(config(),entries=listOf(entry(Kind.PURCHASE,100)))
        val c=Checkpoint("c",later,999_850,"bank")
        assertEquals(-50,b.discrepancy(c)); assertEquals(999_900,b.balances(later).available)
    }
    @Test fun statementPaymentsRequireExplicitAllocation() {
        val s=Statement("s",LocalDate.of(2026,1,1),LocalDate.of(2026,1,25),1_000)
        val a=entry(Kind.PAYMENT,300).copy(statementId="s")
        val b=Book(config(),entries=listOf(a,entry(Kind.PAYMENT,500)),statements=listOf(s))
        b.validate(); assertEquals(700,b.statementRemaining(s,later))
    }
    @Test fun statementCannotUsePaymentBeforeIssueDate() {
        val s=Statement("s",LocalDate.of(2026,2,1),LocalDate.of(2026,2,25),100)
        invalid { Book(config(),entries=listOf(entry(Kind.PAYMENT,100).copy(statementId="s")),statements=listOf(s)).validate() }
    }
    @Test fun cycleCutoffIsRiyadhMidnightOnDayTen() {
        val before=cycle(Instant.parse("2026-02-09T20:59:59Z"),10)
        val after=cycle(Instant.parse("2026-02-09T21:00:00Z"),10)
        assertEquals(LocalDate.of(2026,2,10),before.statementDate)
        assertEquals(LocalDate.of(2026,3,10),after.statementDate)
        assertEquals(before.endExclusive,after.start)
    }
    @Test fun decemberCycleAndLeapYear() {
        assertEquals(LocalDate.of(2027,1,10),cycle(Instant.parse("2026-12-31T12:00:00Z"),10).statementDate)
        assertEquals(LocalDate.of(2024,3,10),cycle(Instant.parse("2024-02-29T12:00:00Z"),10).statementDate)
    }
    @Test fun decimalInputIsExactAndRejectsAmbiguity() {
        assertEquals(123456,money("١٬٢٣٤٫٥٦")); assertEquals(123456,money("۱,۲۳۴.۵۶"))
        assertEquals("0.01",sar(1)); invalid { money("12.345") }; invalid { money("1,23") }; invalid { money("1e3") }
    }
}
