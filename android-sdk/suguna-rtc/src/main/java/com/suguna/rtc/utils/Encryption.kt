package com.suguna.rtc.utils

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import android.util.Log

object Encryption {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12 // GCM standard recommends 12 bytes
    private const val TAG_SIZE = 128 // Authentication tag length (in bits)

    private fun getKey(): SecretKey {
        val keyString = "90083A40204036E21A98F25FDAD274D4A65E4A1A2F70C0B37013DD3FCDE3E277"
        val key = keyString.hexStringToByteArray()
        return SecretKeySpec(key, ALGORITHM)
    }

    private fun String.hexStringToByteArray(): ByteArray {
        val len = this.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i + 1], 16)).toByte()
        }
        return data
    }

    fun encrypt(plainText: Any): String {
        val textToEncrypt = plainText.toString() // Convert the input to a String

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = generateIvFromEmail(textToEncrypt) // Generate IV based on the email
        val key = getKey()
        val spec = GCMParameterSpec(TAG_SIZE, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)

        val encryptedBytes = cipher.doFinal(textToEncrypt.toByteArray(Charsets.UTF_8))

        // Prepend IV to the ciphertext (it's required for decryption)
        val combined = iv + encryptedBytes
        return Base64.encodeToString(combined, Base64.NO_WRAP) // NO_WRAP to avoid line breaks
    }

    private fun generateIvFromEmail(email: String): ByteArray {
        val hash = email.hashCode()
        val iv = ByteArray(IV_SIZE)

        iv[0] = (hash and 0xFF).toByte()
        iv[1] = ((hash shr 8) and 0xFF).toByte()
        iv[2] = ((hash shr 16) and 0xFF).toByte()
        iv[3] = ((hash shr 24) and 0xFF).toByte()

        // Fill remaining bytes (you can choose any deterministic fill)
        for (i in 4 until IV_SIZE) {
            iv[i] = (i * 13).toByte() // e.g., just a dummy fill
        }

        return iv
    }

    fun decrypt(encryptedText: String): String? {
        if (encryptedText.isEmpty()) {
            // Handle the error appropriately
            return null
        }
        val trimmedText = encryptedText.trim()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val combined: ByteArray

        try {
            combined = Base64.decode(trimmedText, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            Log.e("Encryption", "Base64 decode error: ${e.message}")
            return null
        }

        if (combined.size < IV_SIZE) {
            // Handle the case where the text is too short
            return null
        }

        // Extract the IV and the ciphertext
        val iv = combined.sliceArray(0 until IV_SIZE)
        val encryptedBytes = combined.sliceArray(IV_SIZE until combined.size)

        val key = getKey()
        val spec = GCMParameterSpec(TAG_SIZE, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        val decryptedBytes: ByteArray

        try {
            decryptedBytes = cipher.doFinal(encryptedBytes)
        } catch (e: Exception) {
            Log.e("Encryption", "Decryption error: ${e.message}")
            return null
        }

        return String(decryptedBytes, Charsets.UTF_8)
    }

}
