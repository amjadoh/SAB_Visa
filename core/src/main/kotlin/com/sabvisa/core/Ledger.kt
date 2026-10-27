package com.sabvisa.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import java.util.UUID

const val MAX_MONEY = 10_000_000_000L
val RIYADH: ZoneId = ZoneId.of("Asia/Riyadh")
fun digits(value: String): String = value.map { c ->
    when(c) { in '٠'..'٩' -> '0' + (c - '٠'); in '۰'..'۹' -> '0' + (c - '۰'); else -> c }
}.joinToString("")
fun money(value: String): Long {
    val normalized = digits(value.trim()).replace("٬", ",").replace("٫", ".")
    require(Regex("[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?").matches(normalized)) { "أدخل مبلغاً بدقتين عشريتين كحد أقصى" }
    return BigDecimal(normalized.replace(",", "")).movePointRight(2).longValueExact().also {
        require(it in -MAX_MONEY..MAX_MONEY) { "المبلغ خارج النطاق" }
    }
}
fun sar(value: Long): String = BigDecimal.valueOf(value, 2).setScale(2, RoundingMode.UNNECESSARY).toPlainString()
fun id(): String = UUID.randomUUID().toString()

data class Config(
    val limit: Long, val openingOrdinary: Long, val openingAt: Instant,
    val last4: String, val closeDay: Int = 10, val budget: Long = 0,
    val senders: Set<String> = setOf("SAB"), val reminders: Boolean = false
) {
    init {
        require(limit in 1..MAX_MONEY && openingOrdinary in -MAX_MONEY..MAX_MONEY)
        require(last4.matches(Regex("[0-9]{4}")) && closeDay in 1..28 && budget in 0..MAX_MONEY)
        require(senders.isNotEmpty() && senders.size <= 10 && senders.all { it.isNotBlank() && it.length <= 40 })
    }
}
data class Plan(val id: String, val name: String, val principal: Long, val months: Int,
                val firstDue: LocalDate, val createdAt: Instant, val paidBeforeTracking: Int = 0) {
    init { require(name.isNotBlank() && principal in 1..MAX_MONEY && months in 1..120 && principal >= months && paidBeforeTracking in 0..months) }
    fun previousPrincipal(): Long = (0 until paidBeforeTracking).sumOf { installment(it) }
    fun installment(index: Int): Long {
        require(index in 0 until months)
        return principal / months + if (index < principal % months) 1 else 0
    }
    fun due(index: Int): LocalDate = firstDue.plusMonths(index.toLong())
}
enum class Kind { PURCHASE, PAYMENT, REFUND, FEE, CASH, ADJUSTMENT }
data class Entry(val id: String, val kind: Kind, val amount: Long, val at: Instant,
                 val note: String, val sourceId: String? = null, val planId: String? = null,
                 val principalPaid: Long = 0, val statementId: String? = null, val voided: Boolean = false) {
    init {
        require(note.isNotBlank() && note.length <= 300)
        require(amount in -MAX_MONEY..MAX_MONEY && (kind == Kind.ADJUSTMENT || amount > 0))
        require(principalPaid >= 0 && principalPaid <= amount.coerceAtLeast(0))
        require(principalPaid == 0L || (kind == Kind.PAYMENT && planId != null))
        require(planId == null || kind == Kind.PAYMENT)
        require(statementId == null || kind == Kind.PAYMENT)
    }
}
data class Statement(val id: String, val issued: LocalDate, val due: LocalDate, val amount: Long) {
    init { require(due >= issued && amount in 0..MAX_MONEY) }
}
data class Checkpoint(val id: String, val at: Instant, val bankAvailable: Long, val note: String) {
    init { require(bankAvailable in -MAX_MONEY..MAX_MONEY && note.isNotBlank()) }
}
data class LimitChange(val id: String, val at: Instant, val limit: Long) {
    init { require(limit in 1..MAX_MONEY) }
}
data class Book(val config: Config, val plans: List<Plan> = emptyList(), val entries: List<Entry> = emptyList(),
                val statements: List<Statement> = emptyList(), val checkpoints: List<Checkpoint> = emptyList(),
                val limitChanges: List<LimitChange> = emptyList()) {
    fun validate() {
        require(plans.map { it.id }.distinct().size == plans.size)
        require(entries.map { it.id }.distinct().size == entries.size)
        require(statements.map { it.id }.distinct().size == statements.size)
        require(entries.mapNotNull { it.sourceId }.distinct().size == entries.mapNotNull { it.sourceId }.size) { "الرسالة مسجلة سابقاً" }
        require(entries.all { it.at > config.openingAt }) { "العملية ضمن الرصيد الافتتاحي" }
        require(plans.all { it.createdAt >= config.openingAt })
        require(limitChanges.all { it.at >= config.openingAt })
        require(limitChanges.map { it.id }.distinct().size == limitChanges.size)
        entries.filterNot { it.voided }.forEach { e ->
            e.planId?.let { pid -> require(plans.any { it.id == pid && it.createdAt <= e.at }) { "خطة غير موجودة في تاريخ السداد" } }
            e.statementId?.let { sid -> require(statements.any { it.id == sid && e.at.atZone(RIYADH).toLocalDate() >= it.issued }) { "السداد يسبق الكشف" } }
        }
        plans.forEach { p -> require(entries.filter { !it.voided && it.planId == p.id }.sumOf { it.principalPaid } <= p.principal-p.previousPrincipal()) { "تخصيص السداد أكبر من أصل القسط" } }
    }
    fun balances(at: Instant): Balances {
        require(at >= config.openingAt)
        val active = entries.filter { !it.voided && it.at <= at }
        val principal = plans.filter { it.createdAt <= at }.sumOf { p ->
            p.principal - p.previousPrincipal() - active.filter { it.planId == p.id }.sumOf { it.principalPaid }
        }
        val ordinary = active.fold(config.openingOrdinary) { total, e ->
            Math.addExact(total, when(e.kind) {
                Kind.PAYMENT -> -(e.amount - e.principalPaid)
                Kind.REFUND -> -e.amount
                else -> e.amount
            })
        }
        val debt = Math.addExact(ordinary, principal)
        return Balances(ordinary, principal, debt, Math.subtractExact(limitAt(at), debt))
    }
    fun limitAt(at: Instant): Long = limitChanges.filter { it.at <= at }.sortedBy { it.at }.lastOrNull()?.limit ?: config.limit
    fun remaining(p: Plan, at: Instant): Long = p.principal - p.previousPrincipal() - entries.filter {
        !it.voided && it.planId == p.id && it.at <= at
    }.sumOf { it.principalPaid }
    fun duePrincipal(p: Plan, date: LocalDate): Long {
        val scheduled = (p.paidBeforeTracking until p.months).filter { p.due(it-p.paidBeforeTracking) <= date }.sumOf { p.installment(it) }
        val paid = entries.filter { !it.voided && it.planId == p.id && it.at.atZone(RIYADH).toLocalDate() <= date }.sumOf { it.principalPaid }
        return (scheduled - paid).coerceAtLeast(0)
    }
    fun statementRemaining(s: Statement, at: Instant): Long = (s.amount - entries.filter {
        !it.voided && it.kind == Kind.PAYMENT && it.statementId == s.id && it.at <= at
    }.sumOf { it.amount }).coerceAtLeast(0)
    fun spending(from: Instant, until: Instant): Long = entries.filter {
        !it.voided && it.at >= from && it.at < until
    }.sumOf { when(it.kind) { Kind.PURCHASE -> it.amount; Kind.REFUND -> -it.amount; else -> 0L } }
    fun discrepancy(c: Checkpoint): Long = c.bankAvailable - balances(c.at).available
}
data class Balances(val ordinary: Long, val principal: Long, val debt: Long, val available: Long)
data class Cycle(val start: Instant, val endExclusive: Instant, val statementDate: LocalDate)
fun cycle(at: Instant, closeDay: Int): Cycle {
    require(closeDay in 1..28)
    val local = at.atZone(RIYADH)
    val thisClose = local.toLocalDate().withDayOfMonth(closeDay)
    val next = if (local.toLocalDate() < thisClose) thisClose else thisClose.plusMonths(1)
    return Cycle(next.minusMonths(1).atStartOfDay(RIYADH).toInstant(), next.atStartOfDay(RIYADH).toInstant(), next)
}
