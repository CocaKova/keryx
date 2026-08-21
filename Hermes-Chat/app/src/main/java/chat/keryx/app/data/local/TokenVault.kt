package chat.keryx.app.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * At-rest encryption for secrets in SharedPreferences (harvested from Talaria's proven A7 pass —
 * desktop uses Electron's safeStorage; ours is AES/GCM under a key that lives in AndroidKeyStore and never leaves
 * secure hardware where the device has it).
 *
 * Deliberately hand-rolled over androidx.security-crypto: that library is deprecated
 * upstream, and this is ~60 lines against platform APIs (no-decorative-deps).
 *
 * Failure posture — availability over lockout: Keystore is flaky on a minority of devices
 * (vendor keymaster bugs, post-OTA key invalidation). Every failure path degrades to the
 * pre-A7 behavior (plaintext in app-private prefs) instead of eating the token: a gateway
 * client whose token evaporates on every launch is broken in a worse way than one whose
 * token sits where only this app's uid can read it anyway.
 */
object TokenVault {
    private const val ALIAS = "keryx-token-key"
    private const val PREFIX = "gcm1:"
    private const val GCM_TAG_BITS = 128

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Plaintext → "gcm1:<b64 iv>:<b64 ciphertext>"; the input itself on any Keystore failure. */
    fun seal(plain: String): String {
        if (plain.isBlank()) return plain
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX +
                Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ct, Base64.NO_WRAP)
        }.getOrDefault(plain)
    }

    /** Inverse of [seal]. A value without the prefix is legacy plaintext and passes through;
     *  an undecryptable sealed value yields "" (the caller re-auths — never a crash). */
    fun open(stored: String): String {
        if (!stored.startsWith(PREFIX)) return stored
        return runCatching {
            val parts = stored.removePrefix(PREFIX).split(":", limit = 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrDefault("")
    }

    /** True when [stored] is already sealed — used for lazy migration of legacy plaintext. */
    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)
}
