package com.sabvisa.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sabvisa.core.AppUpdate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseUpdatesTest {
    private fun release(code: Int=3, draft: Boolean=false, uploaded: Boolean=true): JSONObject {
        val metadata=JSONObject().put("packageName","com.sabvisa.local").put("versionCode",code).put("versionName","0.1.0-beta.2").put("apkName","SAB_Visa-0.1.0-beta.2.apk")
        return JSONObject().put("draft",draft).put("published_at","2026-01-01T00:00:00Z").put("prerelease",true).put("tag_name","v0.1.0-beta.2")
            .put("body","نسخة اختبار\nSAB_VISA_UPDATE_V1:$metadata")
            .put("assets",JSONArray().put(JSONObject().put("name","SAB_Visa-0.1.0-beta.2.apk").put("state",if(uploaded) "uploaded" else "new").put("size",100)))
    }
    @Test fun recognizesPublishedPrivateBetaAndIgnoresSameOrOlderVersion() {
        val json=JSONArray().put(release()).toString()
        assertEquals(3,ReleaseUpdates.parse(json,2)?.code)
        assertNull(ReleaseUpdates.parse(json,3))
        assertNull(ReleaseUpdates.parse(json,4))
    }
    @Test fun ignoresDraftsMissingApkAndUnsafeTags() {
        val unsafe=release().put("tag_name","v1?token=secret")
        val wrong=release().put("body","SAB_VISA_UPDATE_V1:{\"packageName\":\"other.app\",\"versionCode\":99}")
        assertNull(ReleaseUpdates.parse(JSONArray().put(release(draft=true)).put(release(uploaded=false)).put(unsafe).put(wrong).toString(),2))
    }
    @Test fun choosesHighestVersionRatherThanResponseOrder() {
        assertEquals(5,ReleaseUpdates.parse(JSONArray().put(release(4)).put(release(5)).put(release(3)).toString(),2)?.code)
    }
    @Test fun tokenCacheRoundTripEncryptedAndDisconnectPreservesBook() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val vault=Vault.get(context)
        val before=vault.load()
        val synthetic="synthetic_read_credential_12345"
        try {
            vault.change { it.copy(updateAccess=UpdateAccess(synthetic,AppUpdate(3,"0.1.0-beta.2","v0.1.0-beta.2"),3,"2026-01-01T00:00:00Z")) }
            assertEquals(synthetic,vault.load().updateAccess.token)
            assertFalse(vault.load().updateAccess.toString().contains(synthetic))
            val bytes=java.io.File(context.noBackupFilesDir,"ledger.v1.enc").readBytes()
            assertFalse(String(bytes,Charsets.ISO_8859_1).contains(synthetic))
            vault.change { it.copy(updateAccess=UpdateAccess()) }
            assertEquals("",vault.load().updateAccess.token)
            assertEquals(before.book,vault.load().book)
        } finally { vault.change { before } }
    }
}
