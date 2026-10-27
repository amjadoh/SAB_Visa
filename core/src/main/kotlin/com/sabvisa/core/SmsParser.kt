package com.sabvisa.core

import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

enum class SmsType { PURCHASE, PAYMENT, REFUND, STATEMENT, BALANCE, REVIEW, IGNORE }
data class ParsedSms(val type: SmsType, val amount: Long? = null, val available: Long? = null,
                     val last4: String? = null, val at: Instant, val due: LocalDate? = null,
                     val reason: String, val merchant: String? = null)
fun fingerprint(sender: String, body: String, received: Instant): String {
    val text = sender.trim().uppercase() + "|" + received.toEpochMilli() + "|" + body.trim().replace(Regex("\\s+"), " ")
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
object SmsParser {
    private val number = "([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)(?![0-9.,])"
    private val currency = "(?:SAR|ر\\.?س\\.?|ريال(?:\\s+سعودي)?)"
    private val stamp = DateTimeFormatter.ofPattern("dd-MM-uuuu HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
    private fun amount(text: String): Long? = runCatching {
        val before = Regex("$currency\\s*$number", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
        val after = Regex("$number\\s*$currency", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
        (before ?: after)?.let(::money)
    }.getOrNull()
    fun parse(sender: String, body: String, received: Instant, allowed: Set<String>): ParsedSms {
        if (allowed.none { it.equals(sender.trim(), true) }) return ParsedSms(SmsType.IGNORE, at=received, reason="مرسل غير معتمد")
        val text = digits(body).replace("٫", ".").replace("٬", ",").replace(Regex("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]"), "").replace(Regex("\\s+"), " ").trim()
        if (Regex("رمز التحقق|كلمة المرور|OTP|verification code|one.time.password", RegexOption.IGNORE_CASE).containsMatchIn(text))
            return ParsedSms(SmsType.IGNORE, at=received, reason="رمز تحقق لا يُخزّن")
        if (text.contains("محفظة") || text.contains("حوالة") || text.contains("إيداع"))
            return ParsedSms(SmsType.IGNORE, at=received, reason="محفظة أو حوالة بنكية؛ ليست عملية بطاقة")
        val card = Regex("\\(([0-9]{4})\\*{0,4}\\)|(?:VISA|بطاقة)\\s*[:;]\\s*([0-9]{4})\\*{1,4}", RegexOption.IGNORE_CASE)
            .find(text)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        val timeMatch = Regex("([0-9]{2}:[0-9]{2}:[0-9]{2})\\s+([0-9]{2}-[0-9]{2}-[0-9]{4})|([0-9]{2}-[0-9]{2}-[0-9]{4})\\s+([0-9]{2}:[0-9]{2}:[0-9]{2})").find(text)
        val parsedTime = timeMatch?.let {
            runCatching { LocalDateTime.parse(if(it.groupValues[1].isNotEmpty()) "${it.groupValues[2]} ${it.groupValues[1]}" else "${it.groupValues[3]} ${it.groupValues[4]}", stamp).atZone(RIYADH).toInstant() }.getOrNull()
        }
        if(timeMatch != null && parsedTime == null) return ParsedSms(SmsType.REVIEW, last4=card, at=received, reason="تاريخ عملية غير صالح؛ راجعه يدوياً")
        val at = parsedTime ?: received
        val available = Regex("(?:الرصيد|المتاح)\\s*[:：]\\s*(.*)").find(text)?.groupValues?.get(1)?.let(::amount)
        val supported = Regex("بطاق|VISA|credit card", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (!supported) return ParsedSms(SmsType.IGNORE, at=received, reason="ليست رسالة بطاقة")
        if (Regex("مرفوض|declined|pending|معلق|تفويض", RegexOption.IGNORE_CASE).containsMatchIn(text) && !text.contains("لتنبيهك من أية عمليات مرفوضة"))
            return ParsedSms(SmsType.REVIEW, at=at, last4=card, reason="رفض أو تفويض معلق؛ لا يُسجّل كمشتريات")
        if (text.contains("كشف")) {
            val value = Regex("المبلغ المستحق\\s*[:：]?\\s*(.*)").find(text)?.groupValues?.get(1)?.let(::amount)
            val dueText = Regex("تاريخ الاستحقاق\\s*[:：]?\\s*([0-9]{2}-[0-9]{2}-[0-9]{4})").find(text)?.groupValues?.get(1)
            val due = runCatching { LocalDate.parse(dueText, DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT)) }.getOrNull()
            return ParsedSms(SmsType.STATEMENT, value, last4=card, at=received, due=due, reason="المبلغ المستحق كما سماه البنك؛ يلزم التحقق هل هو كامل الكشف أم الحد الأدنى")
        }
        if (text.contains("الرصيد المتوفر")) return ParsedSms(SmsType.BALANCE, available=amount(text), last4=card, at=received, reason="إشعار متاح قد يكون مقرباً؛ للمقارنة فقط")
        if (text.contains("تسديد") && !text.contains("تأكيد السداد"))
            return ParsedSms(SmsType.IGNORE, at=received, reason="إشعار طلب سداد؛ ننتظر تأكيد السداد لمنع التكرار")
        val type = when {
            text.contains("تأكيد السداد") || text.contains("تم سداد") -> SmsType.PAYMENT
            Regex("استرجاع|عكس عملية|refund|reversal", RegexOption.IGNORE_CASE).containsMatchIn(text) -> SmsType.REFUND
            Regex("شراء|purchase", RegexOption.IGNORE_CASE).containsMatchIn(text) -> SmsType.PURCHASE
            else -> SmsType.REVIEW
        }
        val value = amount(text.substringBefore("الرصيد:"))
        val merchant = Regex("لدى (.*?) بمبلغ").find(text)?.groupValues?.get(1)?.take(160)
        if (type == SmsType.REVIEW || value == null || value <= 0 || !Regex(currency, RegexOption.IGNORE_CASE).containsMatchIn(text) ||
            Regex("USD|EUR|GBP|AED", RegexOption.IGNORE_CASE).containsMatchIn(text))
            return ParsedSms(SmsType.REVIEW, last4=card, at=at, reason="صيغة غير مدعومة أو عملة تحتاج مبلغ الترحيل بالريال")
        return ParsedSms(type, value, available, card, at, reason=if(card == null) "حدد البطاقة يدوياً قبل التسجيل" else "راجع التاريخ والمبلغ والبطاقة", merchant=merchant)
    }
}
