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
    val updateBusy=MutableStateFlow(false)
    val updateStatus=MutableStateFlow("")
    val downloadProgress=MutableStateFlow(-1)
    val downloadReady=MutableStateFlow(false)
    private val cancelDownload=java.util.concurrent.atomic.AtomicBoolean(false)
    private var readyUpdate: AppUpdate?=null
    init { action { vault.current(); refreshSms(); scheduleUpdateChecks(application,vault.current().updateAccess.token.isNotEmpty()); scheduleReminders(application,vault.current().book?.config?.reminders==true); status.value="المتابعة تلقائية من رسائل SMS" } }
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
        vault.change { val b=transform(it.book ?: error("أكمل الإعداد")); val reset=b.config.senders!=it.book!!.config.senders; it.copy(book=b,smsAfter=if(reset) 0 else it.smsAfter,smsId=if(reset) 0 else it.smsId) }; status.value="تم الحفظ محلياً"
    }
    fun importAll(full: Boolean=false) = action { status.value="تمت مزامنة ${importSms(getApplication(),full)} رسالة جديدة تلقائياً" }
    fun syncIfAllowed() {
        if(data.value?.book!=null && getApplication<Application>().checkSelfPermission(android.Manifest.permission.READ_SMS)==android.content.pm.PackageManager.PERMISSION_GRANTED) importAll()
    }
    fun erase() = action { scheduleReminders(getApplication(),false); scheduleUpdateChecks(getApplication(),false); vault.wipe(); fatal.value=false; status.value="حُذفت بيانات التطبيق المحلية" }
    fun connectUpdates(token: String) = updateAction {
        val trimmed=token.trim()
        val available=ReleaseUpdates.fetch(trimmed,ReleaseUpdates.installedCode(getApplication()))
        vault.change { it.copy(updateAccess=UpdateAccess(trimmed,available,checkedAt=Instant.now().toString())) }
        scheduleUpdateChecks(getApplication(),true)
        updateStatus.value="تم ربط تحديثات GitHub الخاصة؛ رمز القراءة محفوظ مشفراً على الجهاز"
    }
    fun disconnectUpdates() = updateAction {
        vault.change { it.copy(updateAccess=UpdateAccess()) }
        scheduleUpdateChecks(getApplication(),false)
        getApplication<Application>().getSystemService(android.app.NotificationManager::class.java).cancel(4)
        UpdateDownload.file(getApplication()).delete(); readyUpdate=null; downloadReady.value=false
        updateStatus.value="أُلغي ربط التحديثات وحُذف الرمز المحلي؛ يمكنك إلغاء الرمز من GitHub أيضاً"
    }
    private fun updateAction(block: ()->Unit) {
        if(updateBusy.value) return
        updateBusy.value=true
        viewModelScope.launch(Dispatchers.IO) {
            try { block() } catch(_: Exception) { updateStatus.value="تعذرت العملية؛ تحقق من الاتصال وصلاحية ربط GitHub وأعد المحاولة" }
            finally { updateBusy.value=false }
        }
    }
    fun checkUpdates() = updateAction {
        updateStatus.value="جارٍ فحص التحديث…"
        updateStatus.value=if(ReleaseUpdates.check(getApplication())) "يتوفر تحديث؛ يمكنك تنزيله وتثبيته هنا" else "أنت على أحدث إصدار متاح لحسابك"
    }
    fun downloadUpdate() = updateAction {
        downloadReady.value=false; downloadProgress.value=0; cancelDownload.set(false)
        try {
            val access=vault.current().updateAccess
            val update=access.available ?: error("افحص التحديث أولاً")
            updateStatus.value="جارٍ التنزيل والتحقق من الملف…"
            UpdateDownload.download(getApplication(),update,access.token,cancelDownload) { downloadProgress.value=it }
            readyUpdate=update; downloadReady.value=true; updateStatus.value="اكتمل التنزيل والتحقق؛ جاهز للتثبيت"
        } catch(e: Exception) {
            if(cancelDownload.get()) updateStatus.value="أُلغي التنزيل؛ يمكنك المحاولة مجدداً" else throw e
        } finally { downloadProgress.value=-1 }
    }
    fun cancelUpdate() { cancelDownload.set(true) }
    fun installUpdate() = updateAction {
        val update=readyUpdate ?: error("أعد تنزيل الملف")
        UpdateDownload.verify(getApplication(),UpdateDownload.file(getApplication()),update)
        updateStatus.value=if(UpdateDownload.install(getApplication())) "أكمل موافقة التثبيت في Android" else "اسمح بالتثبيت لهذا التطبيق ثم ارجع واضغط تثبيت"
    }
    fun changeLimit(limit: Long) = updateBook { b ->
        require(limit in 1..MAX_MONEY) { "أدخل الحد الائتماني الجديد" }
        b.copy(limitChanges=b.limitChanges+LimitChange(id(),Instant.now(),limit))
    }
    fun correctCard(last4: String) = updateBook { it.correctSmsCard(last4) }
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
