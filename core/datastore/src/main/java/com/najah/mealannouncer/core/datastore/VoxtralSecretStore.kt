package com.najah.mealannouncer.core.datastore

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coffre de la clé Mistral (Voxtral).
 *
 * Même mécanisme que [KeystoreSecretStore] — chiffrement AES-GCM par une clé du Keystore
 * matériel — mais **alias et entrées distincts** : deux secrets ne doivent jamais partager un
 * alias, sinon oublier l'un effacerait l'autre. Le fichier de préférences est le même
 * (`mealannouncer-secrets`) ; seules les clés changent.
 *
 * Classe volontairement séparée plutôt que généralisée : le coffre Nightscout est déjà branché
 * dans Hilt et utilisé partout ; on ne le touche pas pour ajouter une voix.
 */
@Singleton
class VoxtralSecretStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : MistralSecretStore {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun hasSecret(): Boolean = prefs.contains(KEY_CIPHERTEXT) && prefs.contains(KEY_IV)

    override fun read(): String? {
        val ciphertext = prefs.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = prefs.getString(KEY_IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val clear = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            String(clear, Charsets.UTF_8)
        } catch (error: Exception) {
            // Clé réinitialisée (restauration, changement de verrouillage) : mieux vaut
            // redemander la clé que de planter à l'ouverture des réglages de la voix.
            clear()
            null
        }
    }

    override fun write(secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        prefs.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
    }

    private fun secretKey(): SecretKey {
        val keystore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let {
            return it.secretKey
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "mealannouncer-secrets"
        const val KEY_CIPHERTEXT = "voxtral.ciphertext"
        const val KEY_IV = "voxtral.iv"
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "mealannouncer.voxtral"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
    }
}
