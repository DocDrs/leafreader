package app.leafreader.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Passwords are never part of the persisted session. */
data class SavedSession(val origin: String, val accountId: String, val deviceId: String,
                        val accessToken: String, val refreshToken: String, val storageOrigin:String = origin) {
    override fun toString() = "SavedSession(redacted)"
    fun validate() {
        require(ServiceOrigin(origin).uri.scheme == "https")
        require(ServiceOrigin(storageOrigin).value==ServiceOrigin(origin).value)
        require(listOf(accountId, deviceId, accessToken, refreshToken).all { it.isNotBlank() && it.length <= 16384 && it.none { c -> c.code < 32 } })
    }
}
sealed interface SessionLoad {
    data object Empty : SessionLoad
    data object Unavailable : SessionLoad
    data class Ready(val session: SavedSession) : SessionLoad
}
interface SessionVault {
    fun load(): SessionLoad
    fun save(session: SavedSession): Boolean
    fun clear()
}
interface SessionCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(sealed: ByteArray): ByteArray
    fun forget() {}
}

/** App-scoped, non-exportable key. No authentication bypass, plaintext fallback or password storage. */
class AndroidSessionCipher : SessionCipher {
    private val alias = "leafreader.bookhub.session.v1"
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun key(create: Boolean): SecretKey {
        val existing = store().getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        if (!create) throw GeneralSecurityException("Session key unavailable")
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        }.generateKey()
    }
    override fun encrypt(plain: ByteArray): ByteArray {
        fun seal(): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(true))
            return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(plain)
        }
        return try { seal() } catch (_: GeneralSecurityException) {
            // Decryption never regenerates a key; save receives a newly accepted or refreshed session.
            store().deleteEntry(alias); seal()
        }
    }
    override fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed.size > 29 && sealed[0].toInt() == 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, sealed.copyOfRange(1, 13)))
        return cipher.doFinal(sealed, 13, sealed.size - 13)
    }
    override fun forget() { store().deleteEntry(alias) }
}

@android.annotation.SuppressLint("ApplySharedPref") // Session replacement/exit must reach disk before another request can run.
class EncryptedSessionVault(context: Context, private val cipher: SessionCipher = AndroidSessionCipher()) : SessionVault {
    private val prefs = context.getSharedPreferences("bookhub-session-sealed-v1", Context.MODE_PRIVATE)
    override fun save(session: SavedSession): Boolean = try {
        session.validate()
        val json = JSONObject().put("version", 1).put("origin", session.origin).put("account", session.accountId)
            .put("storageOrigin",session.storageOrigin).put("device", session.deviceId).put("access", session.accessToken).put("refresh", session.refreshToken)
        val bytes = cipher.encrypt(json.toString().toByteArray(Charsets.UTF_8))
        prefs.edit().putString("sealed", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
    } catch (_: Exception) { runCatching { clear() }; false }
    override fun load(): SessionLoad {
        return try {
            val value = prefs.getString("sealed", null) ?: return SessionLoad.Empty
            require(value.length <= 100000)
            val json = JSONObject(String(cipher.decrypt(Base64.decode(value, Base64.NO_WRAP)), Charsets.UTF_8))
            require(json.getInt("version") == 1)
            val session = SavedSession(json.getString("origin"), json.getString("account"), json.getString("device"), json.getString("access"), json.getString("refresh"),json.optString("storageOrigin",json.getString("origin")))
            session.validate(); SessionLoad.Ready(session)
        } catch (_: Exception) { runCatching { clear() }; SessionLoad.Unavailable }
    }
    override fun clear() {
        try { prefs.edit().remove("sealed").commit() } finally {
            // Cryptographic erasure also makes a stale disk copy unusable after logout/target change.
            try { cipher.forget() } catch (_: Exception) { /* No plaintext fallback. */ }
        }
    }
}
