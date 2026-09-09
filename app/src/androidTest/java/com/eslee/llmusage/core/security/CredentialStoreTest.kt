package com.eslee.llmusage.core.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CredentialStoreTest {
    @Test fun encryptedRoundTripCorruptionAndDeletion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = CredentialStore(context)
        val ref = "instrumentation-test"
        val value = "synthetic-credential-for-local-test".toByteArray()
        store.putSecret(ref, value)
        assertArrayEquals(value, store.getSecret(ref))
        val file = File(context.noBackupFilesDir, "credentials/$ref.bin")
        assertFalse(file.readText().contains(value.toString(Charsets.UTF_8)))
        val ciphertext = file.readBytes()
        ciphertext[ciphertext.lastIndex] = (ciphertext.last().toInt() xor 1).toByte()
        file.writeBytes(ciphertext)
        assertTrue(runCatching { store.getSecret(ref) }.isFailure)
        store.deleteSecret(ref)
        assertNull(store.getSecret(ref))
    }
}
