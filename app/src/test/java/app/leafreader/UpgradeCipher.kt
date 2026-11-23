package app.leafreader

import app.leafreader.data.SessionCipher
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Reproducible synthetic key, test code only; never an app key or Android Keystore simulation. */
class UpgradeCipher : SessionCipher {
    var forgotten = false
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    override fun encrypt(plain: ByteArray): ByteArray {
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key)
        return byteArrayOf(12)+c.iv+c.doFinal(plain)
    }
    override fun decrypt(sealed: ByteArray): ByteArray {
        require(sealed[0].toInt()==12)
        val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key,GCMParameterSpec(128,sealed.copyOfRange(1,13)))
        return c.doFinal(sealed,13,sealed.size-13)
    }
    override fun forget() { forgotten=true }
}
