package com.sabvisa.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class SmsRecord(val id: String, val parsed: ParsedSms, val received: Instant, val status: String = "NEW")
data class AutomaticResult(val book: Book, val records: List<SmsRecord>)
data class LiveBalance(val available: Long, val limit: Long, val anchor: Checkpoint, val estimated: Boolean) {
    val used: Long get() = limit - available
}
data class RepaymentSummary(val purchaseCapacity: Long, val purchasesToClear: Long, val principalInNextPayment: Long,
                            val cyclePrincipal: Long = principalInNextPayment) {
    val paymentToClearPurchases: Long get() = purchasesToClear + principalInNextPayment
    val targetBeforeClose: Long get() = purchasesToClear + cyclePrincipal
}
fun Book.repaymentSummary(at: Instant): RepaymentSummary? {
    val live=liveBalance(at) ?: return null
    val month=billingMonth(at)
    val reserved=plans.filter { it.createdAt<=at }.sumOf { remaining(it,at) }
    val monthly=plans.filter { it.createdAt<=at }.sumOf { p ->
        minOf(remaining(p,at),(monthlyPrincipal(p,month)-principalPaidIn(p,month,at)).coerceAtLeast(0))
    }
    val capacity=live.limit-reserved
    val nextPayment=if(at.atZone(RIYADH).toLocalDate()<installmentDate(at)) 0 else monthly
    return RepaymentSummary(capacity,(capacity-live.available).coerceAtLeast(0),nextPayment,monthly)
}
fun Book.completedInstallments(plan: Plan, at: Instant): Int {
    val paid=plan.principal-remaining(plan,at)
    var accumulated=0L
    return (0 until plan.months).count { index -> accumulated+=plan.installment(index); accumulated<=paid }
}

/** Exact SMS balances are anchors, not another payment. No calendar event moves money. */
fun Book.liveBalance(at: Instant): LiveBalance? {
    val anchor = checkpoints.filter { it.at <= at }.sortedBy { it.at }.lastOrNull() ?: return null
    val later = entries.filter { !it.voided && it.at > anchor.at && it.at <= at && it.kind != Kind.ADJUSTMENT }
    val change = later.sumOf { e -> when(e.kind) {
        Kind.PAYMENT, Kind.REFUND -> e.amount
        else -> -e.amount
    } }
    val limit = limitAt(at)
    val limitDelta = limit - limitAt(anchor.at)
    return LiveBalance(anchor.bankAvailable + change + limitDelta, limit, anchor, later.isNotEmpty() || limitDelta != 0L)
}

fun Book.monthlyPrincipal(plan: Plan, month: YearMonth): Long {
    val offset = ChronoUnit.MONTHS.between(YearMonth.from(plan.firstDue), month)
    val prior=priorAllocations.count { it.planId==plan.id }
    return if(offset < 0 || plan.paidBeforeTracking==plan.months) 0 else plan.installment((offset+plan.paidBeforeTracking+prior).coerceAtMost((plan.months-1).toLong()).toInt())
}
/** The statement month just closed, regardless of when its SMS notification arrives. */
fun Book.billingMonth(at: Instant): YearMonth = YearMonth.from(
    cycle(at,config.closeDay).start.atZone(RIYADH).toLocalDate().minusDays(1))
/** First configured payment day after the last close, not the bank's due date. */
fun Book.installmentDate(at: Instant): LocalDate {
    val start=cycle(at,config.closeDay).start.atZone(RIYADH).toLocalDate()
    fun target(month: YearMonth)=month.atDay(minOf(config.installmentDay,month.lengthOfMonth()))
    val month=YearMonth.from(start)
    return target(month).takeIf { it>=start } ?: target(month.plusMonths(1))
}
fun Book.principalPaidIn(plan: Plan, month: YearMonth, at: Instant = Instant.MAX): Long = entries.filter {
    !it.voided && it.planId == plan.id && it.at <= at && billingMonth(it.at) == month && it.sourceId?.contains(":prior:")!=true
}.sumOf { it.principalPaid }

/** Replay permits old/out-of-order SMS and late plan setup without losing historical records. */
fun applyAutomaticSms(original: Book, records: List<SmsRecord>, now: Instant): AutomaticResult {
    var book = original.copy(
        plans=original.plans.filterNot { it.id.startsWith("auto-plan:") },
        entries=original.entries.filterNot { it.sourceId?.startsWith("auto:") == true },
        statements=original.statements.filterNot { it.id.startsWith("auto:") },
        checkpoints=original.checkpoints.filterNot { it.id.startsWith("auto:") })
    val statuses = mutableMapOf<String,String>()
    val seen = mutableSetOf<String>()
    records.sortedWith(compareBy<SmsRecord> { it.parsed.at }.thenBy { it.received }.thenBy { it.id }).forEach { record ->
        val p=record.parsed
        fun finish(status: String) { statuses[record.id]=status }
        if(record.status in setOf("IGNORED","LEGACY","ACCEPTED")) { finish(if(record.status=="ACCEPTED") "LEGACY" else record.status); return@forEach }
        if(p.type==SmsType.IGNORE) { finish("IGNORED"); return@forEach }
        if(p.at <= book.config.openingAt) { finish("HISTORY"); return@forEach }
        if(p.at > now || record.status=="FUTURE") { finish("FUTURE"); return@forEach }
        if(p.type==SmsType.REVIEW || p.last4==null) { finish("REVIEW"); return@forEach }
        if(p.last4!=book.config.last4) { finish("OTHER_CARD"); return@forEach }
        val semantic="${p.type}|${p.last4}|${p.amount}|${p.at}|${p.merchant.orEmpty()}"
        if(!seen.add(semantic)) { finish("DUPLICATE"); return@forEach }
        val prefix="auto:${record.id}"
        when(p.type) {
            SmsType.PLAN -> {
                val principal=p.amount
                val months=p.months
                if(principal==null || months==null || p.monthly==null) { finish("REVIEW"); return@forEach }
                val plan=Plan("auto-plan:${record.id}","خطة من SAB ${book.plans.size+1}",principal,months,cycle(p.at,book.config.closeDay).statementDate,p.at)
                book=book.copy(plans=book.plans+plan)
            }
            SmsType.PURCHASE, SmsType.REFUND, SmsType.FEE -> {
                val amount=p.amount
                if(amount==null || amount<=0) { finish("REVIEW"); return@forEach }
                val kind=when(p.type) { SmsType.PURCHASE -> Kind.PURCHASE; SmsType.FEE -> Kind.FEE; else -> Kind.REFUND }
                book=book.copy(entries=book.entries+Entry(prefix,kind,amount,p.at,p.merchant?.takeIf { it.isNotBlank() } ?: when(kind) { Kind.PURCHASE -> "شراء من SMS"; Kind.FEE -> "رسوم من SMS"; else -> "استرجاع من SMS" },sourceId=prefix))
            }
            SmsType.PAYMENT -> {
                var left=p.amount ?: 0
                if(left<=0) { finish("REVIEW"); return@forEach }
                val date=p.at.atZone(RIYADH).toLocalDate()
                val month=book.billingMonth(p.at)
                val statement=book.statements.filter { it.issued<=date && book.statementRemaining(it,p.at)>0 }.maxByOrNull { it.issued }
                val prior=book.priorAllocations.filter { it.paymentId==record.id }
                prior.forEach { approval ->
                    val plan=book.plans.singleOrNull { it.id==approval.planId } ?: error("خطة الاعتماد السابق غير موجودة عند السداد")
                    val amount=plan.installment(0)
                    require(approval.approvedAt<=now && plan.paidBeforeTracking==0 && left>=amount && book.remaining(plan,p.at)>=amount) { "دفعة الاعتماد السابق لا تكفي أو تتعارض مع سجل الخطة" }
                    val source="$prefix:prior:${plan.id}"
                    book=book.copy(entries=book.entries+Entry(source,Kind.PAYMENT,amount,p.at,"قسط سابق باعتماد المستخدم؛ بانتظار مطابقة الكشف",sourceId=source,planId=plan.id,principalPaid=amount))
                    left-=amount
                }
                // User tracking rule: wait for the configured payment date; cap per closed cycle.
                if(prior.isEmpty() && date>=book.installmentDate(p.at)) book.plans.sortedWith(compareBy<Plan> { it.createdAt }.thenBy { it.id }).forEach { plan ->
                    if(plan.createdAt<=p.at && left>0) {
                        val cap=(book.monthlyPrincipal(plan,month)-book.principalPaidIn(plan,month,p.at)).coerceAtLeast(0)
                        val paid=minOf(left,cap,book.remaining(plan,p.at))
                        if(paid>0) {
                            val source="$prefix:plan:${plan.id}"
                            book=book.copy(entries=book.entries+Entry(source,Kind.PAYMENT,paid,p.at,"أصل قسط ${plan.name}".take(300),sourceId=source,planId=plan.id,principalPaid=paid,statementId=statement?.id))
                            left-=paid
                        }
                    }
                }
                if(left>0) book=book.copy(entries=book.entries+Entry("$prefix:extra",Kind.PAYMENT,left,p.at,"سداد إضافي من SMS",sourceId="$prefix:extra",statementId=statement?.id))
            }
            SmsType.STATEMENT -> {
                val amount=p.amount
                val issued=p.at.atZone(RIYADH).toLocalDate()
                if(amount==null || p.due==null || p.due<issued || book.statements.any { it.issued==issued }) { finish("REVIEW"); return@forEach }
                book=book.copy(statements=book.statements+Statement(prefix,issued,p.due,amount))
            }
            SmsType.BALANCE -> if(p.available==null) { finish("REVIEW"); return@forEach }
            else -> { finish("REVIEW"); return@forEach }
        }
        if(p.available!=null) book=book.copy(checkpoints=book.checkpoints+Checkpoint("$prefix:balance",p.at,p.available,"الرصيد الوارد في SMS"))
        finish("AUTO")
    }
    book.validate()
    return AutomaticResult(book,records.map { it.copy(status=statuses[it.id] ?: it.status) })
}
