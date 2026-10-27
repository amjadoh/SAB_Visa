package com.sabvisa.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.sabvisa.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Inbox(val id: String, val parsed: ParsedSms, val received: Instant, val status: String = "NEW")
data class VaultData(val book: Book? = null, val inbox: List<Inbox> = emptyList())

/** One process-wide lock covers receiver, worker and UI; write completes before observers change. */
class Vault private constructor(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "ledger.v1.enc"))
    private val alias = "sabvisa.v1"
    private val updates = MutableStateFlow<VaultData?>(null)
    val data = updates.asStateFlow()
    private fun key(create: Boolean): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "مفتاح التخزين غير متاح؛ لم تُستبدل البيانات" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun load(): VaultData {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return VaultData().also { updates.value = it }
        val bytes = file.readFully()
        check(bytes.size > 30 && bytes[0] == 1.toByte()) { "إصدار تخزين غير مدعوم أو ملف تالف" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD("SAB_Visa:v1".toByteArray())
        val result = decode(JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8)))
        result.book?.validate()
        updates.value = result
        return result
    }
    @Synchronized fun change(transform: (VaultData) -> VaultData) {
        val next = transform(load())
        next.book?.validate()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD("SAB_Visa:v1".toByteArray())
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(encode(next).toString().toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
        updates.value = next
    }
    @Synchronized fun ingest(sender: String, body: String, received: Instant): Boolean {
        val current = load()
        val config = current.book?.config ?: return false
        val parsed = SmsParser.parse(sender, body, received, config.senders)
        if (parsed.type == SmsType.IGNORE) return false
        val key = fingerprint(sender, body, parsed.at)
        if (current.inbox.any { it.id == key }) return false
        // Persist extracted fields only. OTP, full account numbers and original SMS are never saved.
        change { it.copy(inbox=it.inbox + Inbox(key, parsed, received)) }
        return true
    }
    @Synchronized fun wipe() {
        file.delete()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        updates.value = VaultData()
    }
    companion object {
        @Volatile private var instance: Vault? = null
        fun get(context: Context): Vault = instance ?: synchronized(this) {
            instance ?: Vault(context.applicationContext).also { instance = it }
        }
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
private fun JSONObject.nullable(name: String): String? = if(isNull(name)) null else getString(name)
private fun obj(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply { pairs.forEach { (k,v) -> put(k,v ?: JSONObject.NULL) } }
private fun encode(v: VaultData): JSONObject = obj("version" to 1,
    "book" to v.book?.let { b -> obj(
        "config" to b.config.let { c -> obj("limit" to c.limit, "ordinary" to c.openingOrdinary, "at" to c.openingAt.toString(), "last4" to c.last4, "day" to c.closeDay, "budget" to c.budget, "senders" to JSONArray(c.senders.toList()), "reminders" to c.reminders) },
        "plans" to JSONArray(b.plans.map { p -> obj("id" to p.id,"name" to p.name,"principal" to p.principal,"months" to p.months,"due" to p.firstDue.toString(),"at" to p.createdAt.toString()) }),
        "entries" to JSONArray(b.entries.map { e -> obj("id" to e.id,"kind" to e.kind.name,"amount" to e.amount,"at" to e.at.toString(),"note" to e.note,"source" to e.sourceId,"plan" to e.planId,"principal" to e.principalPaid,"statement" to e.statementId,"voided" to e.voided) }),
        "statements" to JSONArray(b.statements.map { s -> obj("id" to s.id,"issued" to s.issued.toString(),"due" to s.due.toString(),"amount" to s.amount) }),
        "checkpoints" to JSONArray(b.checkpoints.map { c -> obj("id" to c.id,"at" to c.at.toString(),"amount" to c.bankAvailable,"note" to c.note) })
    ) },
    "inbox" to JSONArray(v.inbox.map { i -> obj("id" to i.id,"received" to i.received.toString(),"status" to i.status,
        "parsed" to i.parsed.let { p -> obj("type" to p.type.name,"amount" to p.amount,"available" to p.available,"last4" to p.last4,"at" to p.at.toString(),"due" to p.due?.toString(),"reason" to p.reason,"merchant" to p.merchant) }) }))
private fun decode(v: JSONObject): VaultData {
    check(v.getInt("version") == 1)
    val book = if(v.isNull("book")) null else v.getJSONObject("book").let { b ->
        val c = b.getJSONObject("config")
        Book(Config(c.getLong("limit"),c.getLong("ordinary"),Instant.parse(c.getString("at")),c.getString("last4"),c.getInt("day"),c.getLong("budget"),c.getJSONArray("senders").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },c.getBoolean("reminders")),
            b.getJSONArray("plans").objects().map { p -> Plan(p.getString("id"),p.getString("name"),p.getLong("principal"),p.getInt("months"),LocalDate.parse(p.getString("due")),Instant.parse(p.getString("at"))) },
            b.getJSONArray("entries").objects().map { e -> Entry(e.getString("id"),Kind.valueOf(e.getString("kind")),e.getLong("amount"),Instant.parse(e.getString("at")),e.getString("note"),e.nullable("source"),e.nullable("plan"),e.getLong("principal"),e.nullable("statement"),e.getBoolean("voided")) },
            b.getJSONArray("statements").objects().map { s -> Statement(s.getString("id"),LocalDate.parse(s.getString("issued")),LocalDate.parse(s.getString("due")),s.getLong("amount")) },
            b.getJSONArray("checkpoints").objects().map { p -> Checkpoint(p.getString("id"),Instant.parse(p.getString("at")),p.getLong("amount"),p.getString("note")) })
    }
    return VaultData(book, v.getJSONArray("inbox").objects().map { i ->
        val p = i.getJSONObject("parsed")
        Inbox(i.getString("id"),ParsedSms(SmsType.valueOf(p.getString("type")),p.nullable("amount")?.toLong(),p.nullable("available")?.toLong(),p.nullable("last4"),Instant.parse(p.getString("at")),p.nullable("due")?.let(LocalDate::parse),p.getString("reason"),p.nullable("merchant")),Instant.parse(i.getString("received")),i.getString("status"))
    })
}
