package com.eslee.llmusage.core.database

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsageDatabaseTest {
    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), UsageDatabase::class.java).build()
    @After fun close() = db.close()
    @Test fun multipleAccountsSnapshotFailureRetentionAndDeleteCascade() = runBlocking {
        val dao = db.dao()
        dao.putAccount(AccountEntity("a", "grok", "{}"))
        dao.putAccount(AccountEntity("b", "grok", "{}"))
        db.withTransaction {
            dao.putSnapshot(SnapshotEntity("old", "a", 10, "old"))
            dao.putBuckets(listOf(BucketEntity("bucket", "old", "weekly", "{}")))
            dao.putCredit(CreditEntity("old", 12.5, "USD"))
        }
        dao.putLog(SyncLogEntity(accountId = "a", startedAt = 20, resultCode = "NETWORK"))
        assertEquals("old", dao.latest("a")?.payload)
        dao.putWidget(WidgetEntity(42, "{}"))
        dao.putWidgetAccounts(listOf(WidgetAccountEntity(42, "a", 0), WidgetAccountEntity(42, "b", 1)))
        dao.pruneSnapshots(100)
        assertEquals(1, dao.history("a").size)
        dao.deleteAccount("a")
        assertNull(dao.account("a"))
        assertTrue(dao.history("a").isEmpty())
        assertEquals(listOf("b"), dao.widgetAccounts(42))
        assertTrue(dao.logs("a").isEmpty())
        assertEquals(1, dao.accounts().size)
    }
    @Test fun failedTransactionDoesNotLeaveHalfSnapshot() = runBlocking {
        val dao = db.dao()
        dao.putAccount(AccountEntity("a", "grok", "{}"))
        runCatching { db.withTransaction {
            dao.putSnapshot(SnapshotEntity("snapshot", "a", 10, "{}"))
            dao.putBuckets(listOf(BucketEntity("bad", "absent", "weekly", "{}")))
        } }
        assertNull(dao.latest("a"))
    }
}
