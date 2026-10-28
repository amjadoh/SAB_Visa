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

@Synchronized fun importSms(context: Context, full: Boolean=false): Int {
    check(context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED) { "صلاحية قراءة الرسائل غير مفعلة؛ استخدم اللصق اليدوي" }
    val vault=Vault.get(context)
    val snapshot=vault.current()
    val config = snapshot.book?.config ?: error("أكمل الإعداد")
    val through=System.currentTimeMillis()
    // Arrival date, not transaction date; overlap includes equal timestamps and late inserts.
    val after=if(full) config.openingAt.toEpochMilli() else maxOf(config.openingAt.toEpochMilli(),snapshot.smsAfter-2*86400_000L)
    val messages=mutableListOf<Triple<String,String,Instant>>()
    var lastId=0L
    // Query only the explicitly approved sender IDs, after the opening snapshot.
    val placeholders = config.senders.joinToString(",") { "?" }
    context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI,
        arrayOf(Telephony.Sms.ADDRESS,Telephony.Sms.BODY,Telephony.Sms.DATE,Telephony.Sms._ID),
        "address COLLATE NOCASE IN ($placeholders) AND date > ? AND date <= ? AND (_id > ? OR date > ?)",
        (config.senders.toList() + listOf(config.openingAt.toEpochMilli().toString(),through.toString(),if(full) "0" else snapshot.smsId.toString(),after.toString())).toTypedArray(), "date ASC")?.use { cursor ->
        while(cursor.moveToNext()) {
            messages+=Triple(cursor.getString(0).orEmpty(),cursor.getString(1).orEmpty(),Instant.ofEpochMilli(cursor.getLong(2)))
            lastId=maxOf(lastId,cursor.getLong(3))
        }
    } ?: error("تعذرت قراءة صندوق الرسائل")
    return vault.ingestBatch(messages,through,lastId)
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
fun notifyLocal(context: Context, id: Int, title: String, text: String): Boolean {
    if(Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
    val manager = context.getSystemService(NotificationManager::class.java)
    val channel=if(id==4) "app-updates" else "local"
    manager.createNotificationChannel(NotificationChannel(channel,if(id==4) "تحديثات التطبيق" else "تذكيرات البطاقة",if(id==4) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_DEFAULT).apply { lockscreenVisibility=Notification.VISIBILITY_PRIVATE })
    if(!manager.areNotificationsEnabled() || manager.getNotificationChannel(channel).importance==NotificationManager.IMPORTANCE_NONE) return false
    val open = PendingIntent.getActivity(context,id,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    manager.notify(id,NotificationCompat.Builder(context,channel).setSmallIcon(com.sabvisa.local.R.drawable.ic_card)
        .setContentTitle(title).setContentText(text).setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
    return true
}
