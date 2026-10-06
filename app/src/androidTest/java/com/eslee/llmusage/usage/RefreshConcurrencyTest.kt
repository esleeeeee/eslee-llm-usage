package com.eslee.llmusage.usage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.core.database.UsageDatabase
import com.eslee.llmusage.core.model.UsageBucket
import com.eslee.llmusage.core.model.UsageSnapshot
import com.eslee.llmusage.core.model.UsageUnit
import com.eslee.llmusage.core.security.CredentialStore
import com.eslee.llmusage.provider.ProviderRegistry
import com.eslee.llmusage.provider.ProviderResult
import com.eslee.llmusage.provider.ProviderErrorCode
import com.eslee.llmusage.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reported after a week of use: choosing an account's main quota did nothing,
 * and a refresh tapped while another was running changed nothing. Both came from
 * the account's collection lock, held for as long as a page took to load.
 */
@RunWith(AndroidJUnit4::class)
class RefreshConcurrencyTest {
    private lateinit var previousSettings: AppSettings

    @Before fun pauseScheduledJobs(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<UsageApplication>()
        previousSettings = app.graph.settings.current()
        app.graph.settings.update(previousSettings.copy(intervalMinutes = 0))
        androidx.work.WorkManager.getInstance(app).cancelAllWork().result.get()
    }

    @After fun restoreSettings(): Unit = runBlocking {
        ApplicationProvider.getApplicationContext<UsageApplication>().graph.settings.update(previousSettings)
    }

    private fun reading(accountId: String, session: Double, weekly: Double) = ProviderResult.Success(UsageSnapshot(
        accountId, "chatgpt", listOf(
            UsageBucket("session", "5-hour usage", unit = UsageUnit.PERCENT, remainingPercent = session),
            UsageBucket("weekly", "Weekly usage", unit = UsageUnit.PERCENT, remainingPercent = weekly),
        ), primaryBucketId = "session",
    ))

    @Test fun choosingTheMainQuotaDuringASlowRefreshTakesEffectAtOnceAndSurvivesTheSave(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<UsageApplication>()
        val database = Room.inMemoryDatabaseBuilder(app, UsageDatabase::class.java).build()
        val loading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = UsageRepository(app, database, ProviderRegistry(false), CredentialStore(app), app.graph.settings) { account, _ ->
            loading.complete(Unit)
            release.await()
            reading(account.id, 76.0, 24.0)
        }
        val id = repository.addAccount("chatgpt", "Plus")
        val refresh = async { repository.refresh(id) }
        try {
            withTimeout(5_000) { loading.await() }
            // The page is still loading; the choice must not wait for it.
            withTimeout(2_000) { repository.updateAccount(repository.account(id)!!.copy(primaryBucketId = "weekly")) }
            assertEquals("weekly", repository.account(id)!!.primaryBucketId)
            release.complete(Unit)
            withTimeout(5_000) { refresh.await() }
            // Saving the reading re-reads the account instead of writing back the copy it began with.
            assertEquals("weekly", repository.account(id)!!.primaryBucketId)
            assertNull(repository.account(id)!!.lastErrorCode)
        } finally {
            release.complete(Unit)
            refresh.await()
            repository.deleteAccount(id)
            database.close()
        }
    }

    @Test fun aRefreshAskedForWhileAnotherRunsWaitsForItsAnswerInsteadOfReturningEmpty(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<UsageApplication>()
        val database = Room.inMemoryDatabaseBuilder(app, UsageDatabase::class.java).build()
        val loads = AtomicInteger()
        val loading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = UsageRepository(app, database, ProviderRegistry(false), CredentialStore(app), app.graph.settings) { account, _ ->
            loads.incrementAndGet()
            loading.complete(Unit)
            release.await()
            reading(account.id, 60.0, 40.0)
        }
        val id = repository.addAccount("chatgpt", "Pro")
        val first = async { repository.refresh(id) }
        try {
            withTimeout(5_000) { loading.await() }
            val second = async { repository.refresh(id) }
            delay(300)
            // The second caller is waiting for the first answer, not already done with nothing.
            assertFalse(second.isCompleted)
            release.complete(Unit)
            withTimeout(5_000) { first.await(); second.await() }
            assertNotNull(repository.latest(id))
            // A successful answer is shared rather than loaded a second time.
            assertEquals(1, loads.get())
        } finally {
            release.complete(Unit)
            first.await()
            repository.deleteAccount(id)
            database.close()
        }
    }

    @Test fun concurrentFailureIsSharedButANewRequestRetries(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<UsageApplication>()
        val database = Room.inMemoryDatabaseBuilder(app, UsageDatabase::class.java).build()
        val loads = AtomicInteger()
        val loading = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = UsageRepository(app, database, ProviderRegistry(false), CredentialStore(app), app.graph.settings) { _, _ ->
            loads.incrementAndGet()
            loading.complete(Unit)
            release.await()
            ProviderResult.Failure(ProviderErrorCode.NETWORK_TIMEOUT, "Synthetic stalled page")
        }
        val id = repository.addAccount("chatgpt", "Failure coalescing")
        val first = async { repository.refresh(id) }
        try {
            withTimeout(5_000) { loading.await() }
            // Run until mutex suspension, so overlap is guaranteed without a timing sleep.
            val second = async(start = CoroutineStart.UNDISPATCHED) { repository.refresh(id) }
            assertFalse(second.isCompleted)
            release.complete(Unit)
            withTimeout(5_000) { first.await(); second.await() }
            assertEquals(1, loads.get())
            assertEquals("NETWORK_TIMEOUT", repository.account(id)!!.lastErrorCode)
            assertEquals(1, repository.logs(id).size)
            assertNull(repository.latest(id))

            withTimeout(5_000) { repository.refresh(id) }
            assertEquals(2, loads.get())
            assertEquals(2, repository.logs(id).size)
            assertEquals("NETWORK_TIMEOUT", repository.account(id)!!.lastErrorCode)
        } finally {
            release.complete(Unit)
            first.await()
            repository.deleteAccount(id)
            database.close()
        }
    }
}
