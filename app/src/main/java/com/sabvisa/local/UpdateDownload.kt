package com.sabvisa.local

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.net.Uri
import androidx.core.content.FileProvider
import com.sabvisa.core.AppUpdate
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Only GitHub receives the read credential; signed asset redirects receive no Authorization. */
object UpdateDownload {
    fun file(context: Context)=File(context.cacheDir,"updates/update.apk")
    fun download(context: Context, update: AppUpdate, token: String, cancelled: AtomicBoolean, progress: (Int)->Unit): File {
        require(update.assetId>0 && update.bytes>0 && update.sha256.isNotEmpty()) { "أعد فحص التحديث للحصول على ملف موثّق" }
        val target=file(context)
        target.parentFile!!.mkdirs()
        val partial=File(target.parentFile,"update.part")
        var complete=false
        try {
            var url=URL("https://api.github.com/repos/amjadoh/SAB_Visa/releases/assets/${update.assetId}")
            var connection: HttpURLConnection?=null
            try {
                for(step in 0..3) {
                    check(!cancelled.get()) { "أُلغي التنزيل" }
                    val c=url.openConnection() as HttpURLConnection
                    connection=c
                    c.instanceFollowRedirects=false; c.connectTimeout=15000; c.readTimeout=15000; c.useCaches=false
                    c.setRequestProperty("Accept","application/octet-stream")
                    c.setRequestProperty("User-Agent","SAB-Visa-Android")
                    if(step==0) c.setRequestProperty("Authorization","Bearer $token")
                    if(c.responseCode==200) break
                    require(c.responseCode in listOf(301,302,303,307,308) && step<3) { "تعذر تنزيل الملف الخاص؛ تحقق من الربط والاتصال" }
                    val next=URL(url,c.getHeaderField("Location") ?: error("رابط تنزيل غير متاح"))
                    require(next.protocol=="https" && next.userInfo==null && next.port in listOf(-1,443) && next.host in setOf("release-assets.githubusercontent.com","objects.githubusercontent.com")) { "وجهة تنزيل غير معتمدة" }
                    c.disconnect(); connection=null; url=next
                }
                val c=connection ?: error("تعذر التنزيل")
                require(c.contentLengthLong<=update.bytes) { "حجم ملف غير مطابق" }
                var total=0L
                val digest=MessageDigest.getInstance("SHA-256")
                c.inputStream.use { input -> partial.outputStream().use { out ->
                    val buffer=ByteArray(65536)
                    while(true) {
                        check(!cancelled.get()) { "أُلغي التنزيل" }
                        val n=input.read(buffer); if(n<0) break
                        total+=n; require(total<=update.bytes) { "حجم الملف يتجاوز الإصدار الموثّق" }
                        out.write(buffer,0,n); digest.update(buffer,0,n)
                        progress((total*100/update.bytes).toInt())
                    }
                } }
                val sha=digest.digest().joinToString("") { "%02x".format(it) }
                require(total==update.bytes && sha==update.sha256) { "لم يجتز الملف فحص السلامة؛ أعد التنزيل" }
                verify(context,partial,update)
                if(target.exists()) check(target.delete())
                check(partial.renameTo(target)) { "تعذر حفظ التحديث" }
                complete=true
                return target
            } finally { connection?.disconnect() }
        } finally { if(!complete) partial.delete() }
    }
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, update: AppUpdate) {
        require(file.length()==update.bytes)
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream -> val b=ByteArray(65536); while(true) { val n=stream.read(b); if(n<0) break; digest.update(b,0,n) } }
        require(digest.digest().joinToString("") { "%02x".format(it) }==update.sha256) { "ملف التحديث تغير؛ أعد التنزيل" }
        val flags=if(Build.VERSION.SDK_INT>=28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm=context.packageManager
        val candidate=pm.getPackageArchiveInfo(file.path,flags) ?: error("ملف تطبيق غير صالح")
        val installed=pm.getPackageInfo(context.packageName,flags)
        val code=if(Build.VERSION.SDK_INT>=28) candidate.longVersionCode else candidate.versionCode.toLong()
        require(candidate.packageName==context.packageName && code==update.code.toLong() && code>ReleaseUpdates.installedCode(context) && candidate.versionName==update.name) { "هوية الإصدار لا تطابق التحديث" }
        fun signatures(info: android.content.pm.PackageInfo): Set<String> {
            val values=if(Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners else info.signatures
            return values.orEmpty().map { it.toCharsString() }.toSet()
        }
        val actual=signatures(candidate)
        require(actual.isNotEmpty() && actual==signatures(installed)) { "توقيع التحديث لا يطابق تطبيقك؛ لم يبدأ التثبيت" }
    }
    fun install(context: Context): Boolean {
        if(Build.VERSION.SDK_INT>=26 && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri=FileProvider.getUriForFile(context,"${context.packageName}.updates",file(context))
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
