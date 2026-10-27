package com.sabvisa.local

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sabvisa.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

class CardViewModel(application: Application): AndroidViewModel(application) {
    val vault = Vault.get(application)
    val data = vault.data
    val status = MutableStateFlow("")
    val busy = MutableStateFlow(false)
    val fatal = MutableStateFlow(false)
    init { action { vault.load(); Unit } }
    fun action(block: () -> Unit) {
        if(busy.value) return
        busy.value=true
        viewModelScope.launch(Dispatchers.IO) {
            try { block(); if(status.value.isEmpty()) status.value="تم الحفظ محلياً" }
            catch(e: Exception) {
                fatal.value = data.value == null
                status.value = if(fatal.value) "تعذر فتح التخزين المشفر. لم تُستبدل بياناتك. أعد المحاولة؛ الحذف متاح من إعدادات Android فقط." else (e.message ?: "تعذرت العملية؛ لم تُغيّر بياناتك")
            } finally { busy.value=false }
        }
    }
    fun updateBook(transform: (Book) -> Book) = action {
        vault.change { it.copy(book=transform(it.book ?: error("أكمل الإعداد"))) }; status.value="تم الحفظ محلياً"
    }
    fun importAll() = action { status.value="أضيفت ${importSms(getApplication())} رسالة جديدة للمراجعة" }
    fun paste(sender: String, body: String, received: Instant) = action {
        status.value=if(vault.ingest(sender,body,received)) "أضيفت للمراجعة" else "تم التجاهل: مرسل غير معتمد أو رمز تحقق أو رسالة غير مالية أو مكررة"
    }
    fun dismiss(item: Inbox) = action { vault.change { d -> d.copy(inbox=d.inbox.map { if(it.id==item.id) it.copy(status="IGNORED") else it }) }; status.value="تم تجاهل الرسالة" }
    fun accept(item: Inbox, entry: Entry? = null, statement: Statement? = null, checkpoint: Checkpoint? = null) = action {
        vault.change { d ->
            check(d.inbox.any { it.id==item.id && it.status=="NEW" }) { "عولجت الرسالة سابقاً" }
            val b=d.book ?: error("أكمل الإعداد")
            val p=item.parsed
            require(p.last4 == null || p.last4==b.config.last4) { "الرسالة لبطاقة أخرى" }
            // Same-value close payments with distinct sender events are flagged, never silently merged.
            if(entry?.kind==Kind.PAYMENT) require(b.entries.none { !it.voided && it.kind==Kind.PAYMENT && it.amount==entry.amount && kotlin.math.abs(it.at.epochSecond-entry.at.epochSecond)<300 }) { "سداد مشابه خلال 5 دقائق؛ راجع السجل وأدخله يدوياً فقط إذا كان مستقلاً" }
            require(listOfNotNull(entry,statement,checkpoint).size==1)
            d.copy(book=b.copy(entries=b.entries+listOfNotNull(entry),statements=b.statements+listOfNotNull(statement),checkpoints=b.checkpoints+listOfNotNull(checkpoint)),
                inbox=d.inbox.map { if(it.id==item.id) it.copy(status="ACCEPTED") else it })
        }; status.value="اعتمدت الرسالة"
    }
    fun erase() = action { scheduleReminders(getApplication(),false); vault.wipe(); fatal.value=false; status.value="حُذفت بيانات التطبيق المحلية" }
}
