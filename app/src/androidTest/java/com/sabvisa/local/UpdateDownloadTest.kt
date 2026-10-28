package com.sabvisa.local

import androidx.test.platform.app.InstrumentationRegistry
import com.sabvisa.core.AppUpdate
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest

class UpdateDownloadTest {
    @Test fun verifiesNewerSameSignerAndRejectsWrongIdentityOrTamperedFile() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val file=File(context.cacheDir,"future-fixture.apk")
        try {
            instrumentation.context.assets.open("future-fixture.apk").use { input -> file.outputStream().use { input.copyTo(it) } }
            val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val good=AppUpdate(ReleaseUpdates.installedCode(context)+1,ReleaseUpdates.installedName(context),"v1.0.1",1,file.length(),hash)
            UpdateDownload.verify(context,file,good)
            try { UpdateDownload.verify(context,file,good.copy(code=good.code+1)); fail("Wrong version must fail") } catch(_: IllegalArgumentException) { }
            try { UpdateDownload.verify(context,file,good.copy(sha256="0".repeat(64))); fail("Wrong digest must fail") } catch(_: IllegalArgumentException) { }
            val bytes=file.readBytes(); bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte(); file.writeBytes(bytes)
            try { UpdateDownload.verify(context,file,good); fail("Tampering must fail") } catch(_: IllegalArgumentException) { }
        } finally { file.delete() }
    }
}
