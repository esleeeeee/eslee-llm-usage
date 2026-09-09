package com.eslee.llmusage.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialStore(context: Context) {
    private val directory = File(context.noBackupFilesDir, "credentials").apply { mkdirs() }
    private val alias = "eslee_llm_usage_master_aes_v1"
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    private fun file(ref: String): AtomicFile {
        require(ref.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        return AtomicFile(File(directory, "$ref.bin"))
    }
    suspend fun putSecret(ref: String, plaintext: ByteArray) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(ref.toByteArray()) }
        val encrypted = cipher.doFinal(plaintext)
        val data = ByteBuffer.allocate(2 + cipher.iv.size + encrypted.size).put(1).put(cipher.iv.size.toByte()).put(cipher.iv).put(encrypted).array()
        val target = file(ref)
        val output = target.startWrite()
        try { output.write(data); target.finishWrite(output) } catch (error: Exception) { target.failWrite(output); throw error }
    }
    suspend fun getSecret(ref: String): ByteArray? = withContext(Dispatchers.IO) {
        val target = file(ref)
        if (!target.baseFile.exists()) return@withContext null
        val bytes = target.readFully()
        require(bytes.size >= 30 && bytes[0] == 1.toByte() && bytes[1] == 12.toByte()) { "Invalid credential envelope" }
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(2, 14)))
            updateAAD(ref.toByteArray())
        }.doFinal(bytes.copyOfRange(14, bytes.size))
    }
    suspend fun deleteSecret(ref: String) = withContext(Dispatchers.IO) { file(ref).delete() }
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        directory.listFiles()?.forEach { check(it.delete()) { "Credential cleanup failed" } }
    }
}
