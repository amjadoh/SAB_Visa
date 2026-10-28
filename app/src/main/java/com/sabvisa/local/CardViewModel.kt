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
    init { action { vault.change { it }; refreshSms(); scheduleUpdateChecks(application,vault.load().updateAccess.token.isNotEmpty()); status.value="المتابعة تلقائية من رسائل SMS" } }
    private fun refreshSms() {
        if(getApplication<Application>().checkSelfPermission(android.Manifest.permission.READ_SMS)==android.content.pm.PackageManager.PERMISSION_GRANTED && vault.load().book!=null) importSms(getApplication())
    }
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
    fun importAll() = action { status.value="تمت مزامنة ${importSms(getApplication())} رسالة جديدة تلقائياً" }
    fun syncIfAllowed() {
        if(data.value?.book!=null && getApplication<Application>().checkSelfPermission(android.Manifest.permission.READ_SMS)==android.content.pm.PackageManager.PERMISSION_GRANTED) importAll()
    }
    fun erase() = action { scheduleReminders(getApplication(),false); scheduleUpdateChecks(getApplication(),false); vault.wipe(); fatal.value=false; status.value="حُذفت بيانات التطبيق المحلية" }
    fun connectUpdates(token: String) = action {
        val trimmed=token.trim()
        val available=ReleaseUpdates.fetch(trimmed,ReleaseUpdates.installedCode(getApplication()))
        vault.change { it.copy(updateAccess=UpdateAccess(trimmed,available,checkedAt=Instant.now().toString())) }
        scheduleUpdateChecks(getApplication(),true)
        status.value="تم ربط تحديثات GitHub الخاصة؛ رمز القراءة محفوظ مشفراً على الجهاز"
    }
    fun disconnectUpdates() = action {
        vault.change { it.copy(updateAccess=UpdateAccess()) }
        scheduleUpdateChecks(getApplication(),false)
        getApplication<Application>().getSystemService(android.app.NotificationManager::class.java).cancel(4)
        status.value="أُلغي ربط التحديثات وحُذف الرمز المحلي؛ يمكنك إلغاء الرمز من GitHub أيضاً"
    }
    fun checkUpdates() = action {
        status.value=if(ReleaseUpdates.check(getApplication())) "يتوفر تحديث؛ استخدم زر التنزيل داخل التطبيق" else "لا يوجد إصدار أحدث في فحص GitHub الحالي"
    }
    fun changeLimit(limit: Long) = updateBook { b ->
        require(limit in 1..MAX_MONEY) { "أدخل الحد الائتماني الجديد" }
        b.copy(limitChanges=b.limitChanges+LimitChange(id(),Instant.now(),limit))
    }
    fun importTracking(uri: android.net.Uri) = action {
        val text=getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
            val bytes=ByteArray(65537)
            var size=0
            while(size<bytes.size) {
                val n=input.read(bytes,size,bytes.size-size)
                if(n<0) break
                size+=n
            }
            require(size<=65536) { "ملف إعدادات التتبع كبير جداً" }
            String(bytes,0,size,Charsets.UTF_8)
        } ?: error("تعذرت قراءة إعدادات التتبع")
        vault.importTrackingProfile(text)
        status.value="اعتمدت إعدادات التتبع؛ يلزم مطابقتها مع الكشف التالي"
    }
    fun approvePrior(planId: String, paymentId: String) = updateBook { b ->
        b.copy(priorAllocations=b.priorAllocations.filterNot { it.planId==planId }+PriorAllocation(planId,paymentId,Instant.now()))
    }
    fun removePrior(planId: String) = updateBook { b -> b.copy(priorAllocations=b.priorAllocations.filterNot { it.planId==planId }) }
}
