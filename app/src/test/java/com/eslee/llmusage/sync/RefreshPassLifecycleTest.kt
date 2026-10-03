package com.eslee.llmusage.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RefreshPassLifecycleTest {
    @Test fun firstAccountFinishingDoesNotClearIndicatorWhileSecondIsPending() = runTest {
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        var finishes = 0
        val pass = launch {
            completeRefreshPass(true, { finishes++ }) {
                listOf(async { first.await() }, async { second.await() }).awaitAll()
            }
        }
        runCurrent()
        first.complete(Unit)
        runCurrent()
        assertEquals(0, finishes)
        assertTrue(pass.isActive)
        second.complete(Unit)
        pass.join()
        assertEquals(1, finishes)
    }

    @Test fun cancellationStillRunsSuspendingIndicatorCleanup() = runTest {
        var finished = false
        var completed: Boolean? = null
        val pass = launch {
            completeRefreshPass(true, { success -> kotlinx.coroutines.yield(); finished = true; completed = success }) {
                CompletableDeferred<Unit>().await()
            }
        }
        runCurrent()
        pass.cancelAndJoin()
        assertTrue(finished)
        assertEquals(false, completed)
    }

    @Test fun emptyCollectionAndFailedCollectionBothReleaseIndicator() = runTest {
        var finishes = 0
        completeRefreshPass(true, { completed -> assertTrue(completed); finishes++ }) { Unit }
        val failure = IllegalStateException("storage unavailable")
        val result = runCatching {
            completeRefreshPass(true, { completed -> assertFalse(completed); finishes++ }) { throw failure }
        }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(2, finishes)
    }

    @Test fun periodicAndSingleAccountPassesDoNotReleaseManualIndicator() = runTest {
        completeRefreshPass(false, { fail("unrelated pass cleared manual indicator") }) { Unit }
    }

    @Test fun widgetHostFailureDoesNotDiscardSuccessfulCollection() = runTest {
        val result = completeRefreshPass(true, { error("host unavailable") }) { "persisted" }
        assertEquals("persisted", result)
    }
}
