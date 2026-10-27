package com.sabvisa.core

import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

enum class SmsType { PURCHASE, PAYMENT, REFUND, FEE, STATEMENT, BALANCE, PLAN, REVIEW, IGNORE }
data class ParsedSms(val type: SmsType, val amount: Long? = null, val available: Long? = null,
                     val last4: String? = null, val at: Instant, val due: LocalDate? = null,
                     val reason: String, val merchant: String? = null, val months: Int? = null, val monthly: Long? = null)
fun fingerprint(sender: String, body: String, received: Instant): String {
    val text = sender.trim().uppercase() + "|" + received.toEpochMilli() + "|" + body.trim().replace(Regex("\\s+"), " ")
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
fun messageFingerprint(sender: String, body: String, parsed: ParsedSms): String =
    fingerprint(sender,body,if(parsed.type==SmsType.PLAN) Instant.EPOCH else parsed.at)
object SmsParser {
    private val number = "([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)(?![0-9.]|,[0-9])"
    private val currency = "(?:SAR|ر\\.?س\\.?|س\\.?ر\\.?|ريال(?:\\s+سعودي)?)"
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
        if (Regex("محفظة|حوالة|إيداع|ايداع|deposit|account credited",RegexOption.IGNORE_CASE).containsMatchIn(text))
            return ParsedSms(SmsType.IGNORE, at=received, reason="محفظة أو حوالة بنكية؛ ليست عملية بطاقة")
        val card = Regex("\\(\\*{0,6}([0-9]{4})\\*{0,6}\\)|(?:VISA|بطاقة)\\s*[:;]\\s*\\*{0,6}([0-9]{4})\\*{0,6}|الائتماني[ةه]\\s*[:;]?\\s*([0-9]{4})(?![0-9])", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
        if(text.contains("تم تحويل العملية الشرائية")) {
            val principal=amount(text.substringBefore("مبلغ القسط"))
            val months=Regex("(?:الى|إلى)\\s*([0-9]{1,3})\\s*قسط").find(text)?.groupValues?.get(1)?.toIntOrNull()
            val monthly=Regex("مبلغ القسط\\s*(.*)").find(text)?.groupValues?.get(1)?.let(::amount)
            val profit=Regex("الربح الشهر[يى]\\s*([0-9]*(?:\\.[0-9]+)?)\\s*%").find(text)?.groupValues?.get(1)?.toBigDecimalOrNull()
            val fees=Regex("([0-9]+(?:\\.[0-9]+)?)\\s*رسوم\\s*[اإ]دار[يى][ةه]").find(text)?.groupValues?.get(1)?.toBigDecimalOrNull()
            if(principal!=null && monthly!=null && months!=null && months in 1..120 && principal>=months && monthly>0 && kotlin.math.abs(principal-monthly*months)<=months && profit?.signum()==0 && fees?.signum()==0)
                return ParsedSms(SmsType.PLAN,principal,last4=card,at=received,reason="تأكيد إنشاء خطة أقساط بلا أرباح أو رسوم",months=months,monthly=monthly)
            return ParsedSms(SmsType.REVIEW,last4=card,at=received,reason="تأكيد تقسيط يحتاج تحققاً من المبلغ أو المدة أو الأرباح والرسوم")
        }
        if(Regex("حوّل|حول عمليتك|للإستفادة|للاستفادة").containsMatchIn(text) && text.contains("أقساط")) return ParsedSms(SmsType.IGNORE,at=received,reason="عرض تقسيط وليس تأكيد إنشاء خطة")
        val isoTime=Regex("([0-9]{4}-[0-9]{2}-[0-9]{2})\\s+([0-9]{2}:[0-9]{2}:[0-9]{2})").find(text)
        val timeMatch = Regex("([0-9]{2}:[0-9]{2}:[0-9]{2})\\s+([0-9]{2}-[0-9]{2}-[0-9]{4})|([0-9]{2}-[0-9]{2}-[0-9]{4})\\s+([0-9]{2}:[0-9]{2}:[0-9]{2})").find(text)
        val parsedTime = isoTime?.let { runCatching { LocalDateTime.parse("${it.groupValues[1]}T${it.groupValues[2]}").atZone(RIYADH).toInstant() }.getOrNull() } ?: timeMatch?.let {
            runCatching { LocalDateTime.parse(if(it.groupValues[1].isNotEmpty()) "${it.groupValues[2]} ${it.groupValues[1]}" else "${it.groupValues[3]} ${it.groupValues[4]}", stamp).atZone(RIYADH).toInstant() }.getOrNull()
        }
        if((timeMatch != null || isoTime != null) && parsedTime == null) return ParsedSms(SmsType.REVIEW, last4=card, at=received, reason="تاريخ عملية غير صالح؛ راجعه يدوياً")
        val at = parsedTime ?: received
        val available = Regex("(?:الرصيد|المتاح)\\s*[:：]\\s*(.*)").find(text)?.groupValues?.get(1)?.let(::amount)
        val supported = Regex("بطاق|VISA|credit card", RegexOption.IGNORE_CASE).containsMatchIn(text)
        if (!supported) return ParsedSms(SmsType.IGNORE, at=received, reason="ليست رسالة بطاقة")
        if (Regex("مرفوض|رُفض|رفضت|رفض|تعذر|لم يتم|لم نتمكن|غير ناجح|غير كاف|رصيد كاف|declined|unsuccessful|pending|معلق|تفويض", RegexOption.IGNORE_CASE).containsMatchIn(text) && !text.contains("لتنبيهك من أية عمليات مرفوضة"))
            return ParsedSms(SmsType.REVIEW, at=at, last4=card, reason="رفض أو تفويض معلق؛ لا يُسجّل كمشتريات")
        if (text.contains("كشف")) {
            val value = Regex("المبلغ المستحق\\s*[:：]?\\s*(.*)").find(text)?.groupValues?.get(1)?.let(::amount)
            val dueText = Regex("تاريخ الاستحقاق\\s*[:：]?\\s*([0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{2}-[0-9]{2}-[0-9]{4})").find(text)?.groupValues?.get(1)
            val due = runCatching { if(dueText?.take(4)?.all { it.isDigit() }==true) LocalDate.parse(dueText) else LocalDate.parse(dueText, DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(ResolverStyle.STRICT)) }.getOrNull()
            return ParsedSms(SmsType.STATEMENT, value, last4=card, at=received, due=due, reason="المبلغ المستحق كما سماه البنك؛ يلزم التحقق هل هو كامل الكشف أم الحد الأدنى")
        }
        if (text.contains("الرصيد المتوفر")) return ParsedSms(SmsType.BALANCE, available=amount(text), last4=card, at=received, reason="إشعار متاح قد يكون مقرباً؛ للمقارنة فقط")
        if (text.contains("تسديد") && !text.contains("تأكيد السداد"))
            return ParsedSms(SmsType.IGNORE, at=received, reason="إشعار طلب سداد؛ ننتظر تأكيد السداد لمنع التكرار")
        val type = when {
            text.contains("تأكيد السداد") || text.contains("تم سداد") -> SmsType.PAYMENT
            text.contains("تم خصم مبلغ") && Regex("Annual Fee|رسوم",RegexOption.IGNORE_CASE).containsMatchIn(text) -> SmsType.FEE
            Regex("استرجاع|عكس عملية|refund|reversal", RegexOption.IGNORE_CASE).containsMatchIn(text) -> SmsType.REFUND
            Regex("^(?:شراء|عملية شراء|purchase)", RegexOption.IGNORE_CASE).containsMatchIn(text) -> SmsType.PURCHASE
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
