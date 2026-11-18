package com.gazefollower.mobile.experiment

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.IOException
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class UploadAuthorization(val address: URI, val token: String)

internal class UploadAuthorizationStore(context: Context) {
    private val settings = context.getSharedPreferences("research_website", Context.MODE_PRIVATE)
    private val alias = "gazefollower_upload_credentials"

    fun lastAddress(): String = settings.getString("address", null)
        ?: "https://gazefollower-staging.onrender.com"

    fun load(): UploadAuthorization? {
        val encoded = settings.getString("encrypted_upload_token", null) ?: return null
        return runCatching {
            val address = URI(lastAddress())
            val sealed = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            cipher.updateAAD(address.toString().toByteArray())
            UploadAuthorization(address, cipher.doFinal(sealed, 12, sealed.size - 12).decodeToString())
        }.getOrNull()
    }

    fun save(authorization: UploadAuthorization) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val address = authorization.address.toString()
        cipher.updateAAD(address.toByteArray())
        val sealed = cipher.iv + cipher.doFinal(authorization.token.toByteArray())
        if (!settings.edit().putString("address", address)
                .putString("encrypted_upload_token", Base64.encodeToString(sealed, Base64.NO_WRAP)).commit()
        ) {
            throw IOException("Could not save the phone pairing. Generate a new code and try again.")
        }
    }

    fun forget() {
        settings.edit().remove("encrypted_upload_token").apply()
    }

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").let { generator ->
            generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generator.generateKey()
        }
    }
}
