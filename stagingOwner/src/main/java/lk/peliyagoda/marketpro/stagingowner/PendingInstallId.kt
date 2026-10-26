package lk.peliyagoda.marketpro.stagingowner

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.UUID

/**
 * An app-INSTALL identity only. Not proof of physical hardware identity.
 * Stored in no-backup private app storage and bound to the AndroidKeystore
 * key generated in this separately signed Staging test application.
 * Never rotates silently. It does not survive uninstall / app-data clear.
 */
internal object PendingInstallId {
    fun getOrCreate(context:Context):String {
        val file=File(context.noBackupFilesDir,"marketpro-stage-owner-install-id-v1")
        val atomic=AtomicFile(file)
        if(file.exists()){
            val id=atomic.openRead().bufferedReader(Charsets.US_ASCII).use { it.readText().trim() }
            require(UUID.fromString(id).toString()==id) { "Invalid saved device ID" }
            return id
        }
        val id=UUID.randomUUID().toString()
        val stream=atomic.startWrite()
        try {
            stream.write(id.toByteArray(Charsets.US_ASCII))
            atomic.finishWrite(stream)
        }catch(e:Exception){
            atomic.failWrite(stream)
            throw e
        }
        return id
    }
}
