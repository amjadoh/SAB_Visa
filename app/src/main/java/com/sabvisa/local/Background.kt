package com.sabvisa.local

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.sabvisa.core.*
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val smsExecutor = Executors.newSingleThreadExecutor()
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return
        val pending = goAsync()
        smsExecutor.execute {
            try {
                val sender = messages.first().originatingAddress ?: return@execute
                val body = messages.joinToString("") { it.messageBody.orEmpty() }
                val added = Vault.get(context).ingest(sender, body, Instant.ofEpochMilli(messages.first().timestampMillis))
                if (added) notifyLocal(context, 2, "تحديث البطاقة", "عولجت رسالة بطاقة جديدة محلياً؛ افتح التطبيق للاطلاع على التحديث.")
            } catch (_: Exception) {
                // Never log SMS or reset the encrypted ledger on failure.
                notifyLocal(context, 3, "تعذّر حفظ الرسالة", "افتح التطبيق ثم أعد الاستيراد عند توفر التخزين.")
            } finally { pending.finish() }
        }
    }
}

fun importSms(context: Context): Int {
    check(context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) { "صلاحية قراءة الرسائل غير مفعلة؛ استخدم اللصق اليدوي" }
    val config = Vault.get(context).load().book?.config ?: error("أكمل الإعداد")
    var count = 0
    // Query only the explicitly approved sender IDs, after the opening snapshot.
    val placeholders = config.senders.joinToString(",") { "?" }
    context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
        arrayOf(Telephony.Sms.ADDRESS,Telephony.Sms.BODY,Telephony.Sms.DATE),
        "address COLLATE NOCASE IN ($placeholders) AND date > ?",
        (config.senders.toList() + config.openingAt.toEpochMilli().toString()).toTypedArray(), "date ASC")?.use { cursor ->
        while(cursor.moveToNext()) {
            if(Vault.get(context).ingest(cursor.getString(0).orEmpty(),cursor.getString(1).orEmpty(),Instant.ofEpochMilli(cursor.getLong(2)))) count++
        }
    } ?: error("تعذرت قراءة صندوق الرسائل")
    return count
}
fun scheduleReminders(context: Context, enabled: Boolean) {
    val manager = WorkManager.getInstance(context)
    if(!enabled) { manager.cancelUniqueWork("card-reminders"); return }
    manager.enqueueUniquePeriodicWork("card-reminders",ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<ReminderWorker>(12,TimeUnit.HOURS).build())
}
class ReminderWorker(context: Context, params: WorkerParameters): Worker(context,params) {
    override fun doWork(): Result = try {
        val b = Vault.get(applicationContext).load().book
        if(b?.config?.reminders == true) {
            val today = LocalDate.now(RIYADH)
            val next = cycle(Instant.now(),b.config.closeDay).statementDate
            val closeSoon = today >= next.minusDays(3)
            val target = today.withDayOfMonth(minOf(b.config.installmentDay,today.lengthOfMonth()))
            val month = b.billingMonth(Instant.now())
            val installmentSoon = today >= target.minusDays(2) && today <= target && b.plans.any {
                b.remaining(it,Instant.now()) > 0 && b.monthlyPrincipal(it,month) > b.principalPaidIn(it,month,Instant.now())
            }
            val dueSoon = b.statements.any { !it.due.isBefore(today) && it.due <= today.plusDays(3) && b.statementRemaining(it,Instant.now()) > 0 }
            val overdue = b.statements.any { it.due < today && b.statementRemaining(it,Instant.now()) > 0 }
            if(closeSoon || dueSoon || overdue || installmentSoon) notifyLocal(applicationContext,1,"مراجعة البطاقة", "راجع الكشف وموعد القسط المخطط وتاريخ الاستحقاق داخل التطبيق.")
        }
        Result.success()
    } catch (_: Exception) { Result.failure() }
}
fun notifyLocal(context: Context, id: Int, title: String, text: String) {
    if(Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("local", "تذكيرات البطاقة",NotificationManager.IMPORTANCE_DEFAULT).apply { lockscreenVisibility=Notification.VISIBILITY_PRIVATE })
    val open = PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    manager.notify(id,NotificationCompat.Builder(context,"local").setSmallIcon(com.sabvisa.local.R.drawable.ic_card)
        .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
}
