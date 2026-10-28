package com.sabvisa.local

import android.content.Context
import android.os.Build
import androidx.work.*
import com.sabvisa.core.AppUpdate
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.TimeUnit

data class UpdateAccess(val token: String="", val available: AppUpdate?=null, val notifiedCode: Int=0, val checkedAt: String="") {
    override fun toString() = "UpdateAccess(token=[redacted], available=$available)"
}

object ReleaseUpdates {
    const val tokenSetupUrl="https://github.com/settings/personal-access-tokens/new?name=SAB_Visa_updates&target_name=amjadoh&contents=read&expires_in=90"
    fun installedName(context: Context): String = context.packageManager.getPackageInfo(context.packageName,0).versionName ?: "غير محدد"
    @Suppress("DEPRECATION") fun installedCode(context: Context): Int {
        val info=context.packageManager.getPackageInfo(context.packageName,0)
        return if(Build.VERSION.SDK_INT>=28) info.longVersionCode.toInt() else info.versionCode
    }
    fun parse(text: String, installed: Int): AppUpdate? {
        val releases=JSONArray(text)
        return (0 until releases.length()).mapNotNull { index ->
            runCatching {
                val release=releases.getJSONObject(index)
                if(release.optBoolean("draft",true) || release.isNull("published_at")) return@runCatching null
                val line=release.optString("body").lineSequence().singleOrNull { it.startsWith("SAB_VISA_UPDATE_V1:") } ?: return@runCatching null
                val metadata=JSONObject(line.removePrefix("SAB_VISA_UPDATE_V1:"))
                require(metadata.getString("packageName")=="com.sabvisa.local")
                val code=metadata.get("versionCode")
                require(code is Int || code is Long)
                val number=(code as Number).toLong()
                require(number in 1..Int.MAX_VALUE.toLong())
                val apk=metadata.getString("apkName")
                require(apk.matches(Regex("SAB_Visa-[0-9A-Za-z.+_-]+\\.apk")))
                val assets=release.getJSONArray("assets")
                val asset=(0 until assets.length()).map { assets.getJSONObject(it) }.singleOrNull {
                    it.optString("name")==apk && it.optString("state")=="uploaded" && it.optLong("size") in 1..104857600
                } ?: return@runCatching null
                val digest=asset.optString("digest").removePrefix("sha256:").takeIf { it.matches(Regex("[a-f0-9]{64}")) }.orEmpty()
                AppUpdate(number.toInt(),metadata.getString("versionName"),release.getString("tag_name"),asset.optLong("id"),asset.getLong("size"),digest).takeIf { it.newerThan(installed) }
            }.getOrNull()
        }.maxByOrNull { it.code }
    }
    fun fetch(token: String, installed: Int): AppUpdate? {
        require(token.matches(Regex("[A-Za-z0-9_]{20,255}"))) { "أدخل رمز GitHub صالحاً بصلاحية قراءة المستودع فقط" }
        // Fixed destination, GET only, no redirects: the credential never goes to an asset host.
        val connection=URL("https://api.github.com/repos/amjadoh/SAB_Visa/releases?per_page=20").openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects=false
            connection.useCaches=false
            connection.connectTimeout=10_000; connection.readTimeout=10_000
            connection.setRequestProperty("Accept","application/vnd.github+json")
            connection.setRequestProperty("Authorization","Bearer $token")
            connection.setRequestProperty("User-Agent","SAB-Visa-Android")
            connection.setRequestProperty("X-GitHub-Api-Version","2022-11-28")
            when(connection.responseCode) {
                200 -> Unit
                401,403,404 -> error("تعذر الوصول الخاص: تحقق من صلاحية الرمز وانتهائه واختيار مستودع SAB_Visa")
                else -> error("تعذر فحص التحديث حالياً؛ أعد المحاولة لاحقاً")
            }
            val bytes=connection.inputStream.use { input ->
                val buffer=ByteArray(262145)
                var size=0
                while(size<buffer.size) {
                    val count=input.read(buffer,size,buffer.size-size)
                    if(count<0) break
                    size+=count
                }
                require(size<=262144) { "استجابة التحديث أكبر من الحد المسموح" }
                buffer.copyOf(size)
            }
            return parse(String(bytes,Charsets.UTF_8),installed)
        } finally { connection.disconnect() }
    }
    fun check(context: Context): Boolean {
        val vault=Vault.get(context)
        val access=vault.current().updateAccess
        check(access.token.isNotEmpty()) { "اربط GitHub لفحص التحديثات الخاصة" }
        val found=fetch(access.token,installedCode(context))
        var notify=false
        vault.change { current ->
            if(current.updateAccess.token!=access.token) current else {
                notify=found!=null && found.code>current.updateAccess.notifiedCode
                current.copy(updateAccess=UpdateAccess(access.token,found,current.updateAccess.notifiedCode,Instant.now().toString()))
            }
        }
        if(notify && found!=null && notifyLocal(context,4,"يتوفر تحديث لـ SAB Visa","الإصدار ${found.name} متاح؛ افتح التطبيق للوصول إلى التنزيل الخاص.")) {
            vault.change { current ->
                val a=current.updateAccess
                if(a.token!=access.token) current else current.copy(updateAccess=UpdateAccess(a.token,a.available,maxOf(a.notifiedCode,found.code),a.checkedAt))
            }
        }
        return found!=null
    }
}

fun scheduleUpdateChecks(context: Context, enabled: Boolean) {
    val manager=WorkManager.getInstance(context)
    if(!enabled) {
        manager.cancelUniqueWork("private-update-check")
        manager.cancelUniqueWork("private-update-periodic")
        return
    }
    val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    manager.enqueueUniquePeriodicWork("private-update-periodic",ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<UpdateWorker>(24,TimeUnit.HOURS).setConstraints(constraints).build())
    manager.enqueueUniqueWork("private-update-check",ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(constraints).build())
}
class UpdateWorker(context: Context, params: WorkerParameters): Worker(context,params) {
    override fun doWork(): Result = try {
        ReleaseUpdates.check(applicationContext)
        Result.success()
    } catch (_: Exception) { if(runAttemptCount<2) Result.retry() else Result.failure() }
}
