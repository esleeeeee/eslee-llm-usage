package com.eslee.llmusage.usage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RefreshAccountsIsolationTest {
    @Test fun storageFailureWaitsForOtherAccountBeforePropagating() = runTest {
        val release = CompletableDeferred<Unit>()
        val failure = IllegalStateException("storage unavailable")
        var secondSaved = false
        var reported: Throwable? = null
        val pass = launch {
            try {
                refreshAccountsIndependently(listOf(1, 2)) {
                    if (it == 1) throw failure
                    release.await()
                    secondSaved = true
                }
            } catch (error: Exception) { reported = error }
        }
        runCurrent()
        assertTrue(pass.isActive)
        assertNull(reported)
        release.complete(Unit)
        pass.join()
        assertTrue(secondSaved)
        // Coroutine stack-trace recovery may copy the exception across suspension.
        assertEquals(failure.javaClass, reported?.javaClass)
        assertEquals(failure.message, reported?.message)
    }

    @Test fun cancellationImmediatelyCancelsOtherAccount() = runTest {
        val cancel = CompletableDeferred<Unit>()
        var secondCancelled = false
        val pass = launch {
            refreshAccountsIndependently(listOf(1, 2)) {
                if (it == 1) {
                    cancel.await()
                    throw CancellationException("worker stopped")
                }
                try { CompletableDeferred<Unit>().await() }
                finally { secondCancelled = true }
            }
        }
        runCurrent()
        cancel.complete(Unit)
        pass.join()
        assertTrue(pass.isCancelled)
        assertTrue(secondCancelled)
    }
}
