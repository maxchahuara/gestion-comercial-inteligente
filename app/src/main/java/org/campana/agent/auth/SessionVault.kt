package org.campana.agent.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.campana.agent.net.json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
data class Session(
    val clientId: String, val subject: String, val email: String,
    val accessToken: String, val refreshToken: String, val idToken: String,
    val expiresAt: Long, val scopes: String
) {
    override fun toString():String="Session(clientId="+clientId+", credentials=[redacted])"
}
@Serializable data class VaultData(val active: String = "", val sessions: List<Session> = emptyList())

class SessionVault(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "identity.bin"))
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("campana.identity.v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("campana.identity.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(): VaultData {
        if (!file.baseFile.exists()) return VaultData()
        val bytes = file.readFully()
        require(bytes.size > 28) { "El almacén de credenciales está dañado." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
        return json.decodeFromString(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)), Charsets.UTF_8))
    }
    @Synchronized fun save(data: VaultData) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.iv + cipher.doFinal(json.encodeToString(data).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) } catch (e: Exception) { file.failWrite(stream); throw e }
    }
    fun active(): Session? = read().let { d -> d.sessions.firstOrNull { it.clientId == d.active } }
    @Synchronized fun put(session: Session, activate: Boolean = true) {
        val old = read()
        save(old.copy(active = if (activate) session.clientId else old.active,
            sessions = old.sessions.filterNot { it.clientId == session.clientId } + session))
    }
    @Synchronized fun select(clientId: String) {
        val old = read(); require(old.sessions.any { it.clientId == clientId }); save(old.copy(active=clientId))
    }
}
