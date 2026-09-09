package com.eslee.llmusage.core.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "accounts")
data class AccountEntity(@PrimaryKey val id: String, val providerId: String, val payload: String)

@Entity(tableName = "snapshots", foreignKeys = [ForeignKey(entity = AccountEntity::class,
    parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["accountId", "fetchedAt"])])
data class SnapshotEntity(@PrimaryKey val id: String, val accountId: String, val fetchedAt: Long, val payload: String)

@Entity(tableName = "buckets", foreignKeys = [ForeignKey(entity = SnapshotEntity::class,
    parentColumns = ["id"], childColumns = ["snapshotId"], onDelete = ForeignKey.CASCADE)], indices = [Index("snapshotId")])
data class BucketEntity(@PrimaryKey val id: String, val snapshotId: String, val providerBucketId: String, val payload: String)

@Entity(tableName = "credits", foreignKeys = [ForeignKey(entity = SnapshotEntity::class,
    parentColumns = ["id"], childColumns = ["snapshotId"], onDelete = ForeignKey.CASCADE)], indices = [Index("snapshotId")])
data class CreditEntity(@PrimaryKey val snapshotId: String, val amount: Double, val currency: String)

@Entity(tableName = "widgets")
data class WidgetEntity(@PrimaryKey val appWidgetId: Int, val payload: String)

@Entity(tableName = "widget_accounts", primaryKeys = ["appWidgetId", "accountId"], foreignKeys = [
    ForeignKey(entity = WidgetEntity::class, parentColumns = ["appWidgetId"], childColumns = ["appWidgetId"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = AccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)
], indices = [Index("accountId")])
data class WidgetAccountEntity(val appWidgetId: Int, val accountId: String, val position: Int)

@Entity(tableName = "sync_logs", foreignKeys = [ForeignKey(entity = AccountEntity::class,
    parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.CASCADE)], indices = [Index("accountId")])
data class SyncLogEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val accountId: String,
    val startedAt: Long, val resultCode: String, val parserVersion: String? = null)

data class OverviewRow(@Embedded val account: AccountEntity, val snapshotPayload: String?)

@Dao
interface UsageDao {
    @Query("SELECT accounts.*, (SELECT payload FROM snapshots WHERE accountId=accounts.id ORDER BY fetchedAt DESC, id DESC LIMIT 1) AS snapshotPayload FROM accounts ORDER BY rowid")
    fun observeOverview(): Flow<List<OverviewRow>>
    @Query("SELECT * FROM accounts ORDER BY rowid") suspend fun accounts(): List<AccountEntity>
    @Query("SELECT * FROM accounts WHERE id=:id") suspend fun account(id: String): AccountEntity?
    @Upsert suspend fun putAccount(entity: AccountEntity)
    @Query("DELETE FROM accounts WHERE id=:id") suspend fun deleteAccount(id: String)
    @Query("SELECT * FROM snapshots WHERE accountId=:id ORDER BY fetchedAt DESC, id DESC LIMIT 1") suspend fun latest(id: String): SnapshotEntity?
    @Query("SELECT * FROM snapshots WHERE accountId=:id ORDER BY fetchedAt DESC") suspend fun history(id: String): List<SnapshotEntity>
    @Insert suspend fun putSnapshot(entity: SnapshotEntity)
    @Insert suspend fun putBuckets(entities: List<BucketEntity>)
    @Insert suspend fun putCredit(entity: CreditEntity)
    @Query("DELETE FROM snapshots WHERE fetchedAt < :before AND id NOT IN (SELECT s.id FROM snapshots s WHERE s.id = (SELECT s2.id FROM snapshots s2 WHERE s2.accountId=s.accountId ORDER BY s2.fetchedAt DESC, s2.id DESC LIMIT 1))")
    suspend fun pruneSnapshots(before: Long)
    @Insert suspend fun putLog(entity: SyncLogEntity)
    @Query("SELECT * FROM sync_logs WHERE (:accountId IS NULL OR accountId=:accountId) ORDER BY startedAt DESC LIMIT 200") suspend fun logs(accountId: String?): List<SyncLogEntity>
    @Query("DELETE FROM sync_logs WHERE id NOT IN (SELECT id FROM sync_logs ORDER BY startedAt DESC, id DESC LIMIT 200)") suspend fun pruneLogs()
    @Upsert suspend fun putWidget(entity: WidgetEntity)
    @Query("SELECT * FROM widgets WHERE appWidgetId=:id") suspend fun widget(id: Int): WidgetEntity?
    @Query("SELECT * FROM widgets ORDER BY appWidgetId") suspend fun widgets(): List<WidgetEntity>
    @Query("DELETE FROM widgets WHERE appWidgetId=:id") suspend fun deleteWidget(id: Int)
    @Query("DELETE FROM widget_accounts WHERE appWidgetId=:id") suspend fun clearWidgetAccounts(id: Int)
    @Insert suspend fun putWidgetAccounts(accounts: List<WidgetAccountEntity>)
    @Query("SELECT accountId FROM widget_accounts WHERE appWidgetId=:id ORDER BY position") suspend fun widgetAccounts(id: Int): List<String>
}

@Database(entities = [AccountEntity::class, SnapshotEntity::class, BucketEntity::class, CreditEntity::class,
    WidgetEntity::class, WidgetAccountEntity::class, SyncLogEntity::class], version = 1, exportSchema = true)
abstract class UsageDatabase : RoomDatabase() {
    abstract fun dao(): UsageDao
}
