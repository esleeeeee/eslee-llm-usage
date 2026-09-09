package com.eslee.llmusage.usage

import android.content.Context
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.eslee.llmusage.core.database.*
import com.eslee.llmusage.core.model.*
import com.eslee.llmusage.core.security.CredentialStore
import com.eslee.llmusage.core.web.ProfileSessions
import com.eslee.llmusage.provider.*
import com.eslee.llmusage.settings.SettingsStore
import com.eslee.llmusage.widget.updateWidgets
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class AccountOverview(val account: Account, val snapshot: UsageSnapshot?)
data class SyncLog(val accountId: String, val startedAt: Long, val resultCode: String, val parserVersion: String? = null)

class UsageRepository(
    private val context: Context,
    private val database: UsageDatabase,
    private val registry: ProviderRegistry,
    private val credentials: CredentialStore,
    private val settings: SettingsStore,
) {
    private val dao = database.dao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lifecycleLock = Mutex()
    private val retryAt = ConcurrentHashMap<String, Long>()
    val accounts = dao.observeOverview().map { rows -> rows.map { AccountOverview(
        json.decodeFromString<Account>(it.account.payload), it.snapshotPayload?.let { payload -> json.decodeFromString<UsageSnapshot>(payload) }) } }
    suspend fun account(id: String) = dao.account(id)?.let { json.decodeFromString<Account>(it.payload) }
    suspend fun latest(id: String) = dao.latest(id)?.let { json.decodeFromString<UsageSnapshot>(it.payload) }
    suspend fun history(id: String) = dao.history(id).map { json.decodeFromString<UsageSnapshot>(it.payload) }
    suspend fun logs(id: String? = null) = dao.logs(id).map { SyncLog(it.accountId, it.startedAt, it.resultCode, it.parserVersion) }
    private suspend fun persist(account: Account) = dao.putAccount(AccountEntity(account.id, account.providerId, json.encodeToString(account)))

    suspend fun addAccount(providerId: String, alias: String, secret: String? = null, teamId: String? = null): String = lifecycleLock.withLock {
        val definition = requireNotNull(registry.definition(providerId))
        require(definition.supported)
        if (definition.authMode == AuthMode.WEB_PROFILE) {
            check(withContext(Dispatchers.Main) { ProfileSessions.supported() }) { "WEBVIEW_UNSUPPORTED" }
        }
        val id = UUID.randomUUID().toString()
        val account = Account(id, providerId, alias.trim().take(80).ifEmpty { "${definition.displayName} ${dao.accounts().count { it.providerId == providerId } + 1}" },
            definition.authMode, if (definition.authMode == AuthMode.WEB_PROFILE) "llmusage_${providerId}_$id" else null,
            teamId?.trim()?.takeIf { it.isNotEmpty() }, lastErrorCode = if (definition.authMode == AuthMode.API_KEY) "NEEDS_VALIDATION" else "AUTH_REQUIRED")
        try {
            if (!secret.isNullOrBlank()) {
                val bytes = secret.toByteArray()
                try { credentials.putSecret(id, bytes) } finally { bytes.fill(0) }
            }
            persist(account)
        } catch (error: Exception) { credentials.deleteSecret(id); throw error }
        id
    }

    suspend fun updateAccount(account: Account) = locks.getOrPut(account.id) { Mutex() }.withLock {
        val existing = this.account(account.id) ?: return@withLock
        persist(existing.copy(alias = account.alias.trim().take(80).ifEmpty { existing.alias }, enabled = account.enabled,
            primaryBucketId = account.primaryBucketId))
        updateWidgets(context)
    }
    suspend fun setCredential(id: String, secret: String) = locks.getOrPut(id) { Mutex() }.withLock {
        val account = account(id) ?: return@withLock
        require(account.authMode == AuthMode.API_KEY && secret.isNotBlank())
        val bytes = secret.toByteArray()
        try { credentials.putSecret(id, bytes) } finally { bytes.fill(0) }
        persist(account.copy(lastErrorCode = "NEEDS_VALIDATION"))
    }

    suspend fun refresh(id: String) {
        val mutex = locks.getOrPut(id) { Mutex() }
        // Coalesce repeated taps: the active request will emit its result to all observers.
        if (!mutex.tryLock()) return
        try {
            val account = account(id)?.takeIf { it.enabled } ?: return
            if ((retryAt[id] ?: 0) > System.currentTimeMillis()) return
            val started = System.currentTimeMillis()
            val result = try {
                if (registry.definition(account.providerId)?.capabilities?.supportsBackgroundSync != true) {
                    ProviderResult.Failure(ProviderErrorCode.UNSUPPORTED, "FOREGROUND_REQUIRED")
                } else {
                    val bytes = credentials.getSecret(id)
                    try { registry.adapter(account.providerId)?.fetch(account, bytes?.toString(Charsets.UTF_8).orEmpty())
                        ?: ProviderResult.Failure(ProviderErrorCode.UNSUPPORTED, "") }
                    finally { bytes?.fill(0) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { ProviderResult.Failure(ProviderErrorCode.CONFIGURATION, "") }
            saveResult(account, result, started)
        } finally { mutex.unlock() }
        updateWidgets(context)
    }

    suspend fun refreshAll() = supervisorScope {
        dao.accounts().map { json.decodeFromString<Account>(it.payload) }
            .filter { it.enabled && registry.definition(it.providerId)?.capabilities?.supportsBackgroundSync == true }
            .map { account -> async { try { refresh(account.id) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { } } }.awaitAll()
        cleanup()
    }

    suspend fun recordWeb(id: String, text: String) {
        locks.getOrPut(id) { Mutex() }.withLock {
            val account = account(id)?.takeIf { it.enabled && it.authMode == AuthMode.WEB_PROFILE } ?: return
            val started = System.currentTimeMillis()
            val result = withContext(Dispatchers.Default) { ConsumerUsageParser.parse(account.providerId, id, text, started) }
            saveResult(account, result, started)
        }
        updateWidgets(context)
    }

    private suspend fun saveResult(account: Account, result: ProviderResult, started: Long) {
        database.withTransaction {
            if (dao.account(account.id) == null) return@withTransaction
            when (result) {
                is ProviderResult.Success -> {
                    val snapshot = result.snapshot.copy(buckets = result.snapshot.buckets.map(UsageNormalizer::normalize))
                    dao.putSnapshot(SnapshotEntity(snapshot.snapshotId, account.id, snapshot.fetchedAt, json.encodeToString(snapshot)))
                    dao.putBuckets(snapshot.buckets.map { BucketEntity(UUID.randomUUID().toString(), snapshot.snapshotId, it.id, json.encodeToString(it)) })
                    snapshot.extraCredits?.let { dao.putCredit(CreditEntity(snapshot.snapshotId, it.amount, it.currency)) }
                    persist(account.copy(lastAttemptAt = started, lastSuccessAt = snapshot.fetchedAt, lastErrorCode = null))
                    dao.putLog(SyncLogEntity(accountId = account.id, startedAt = started, resultCode = "SUCCESS",
                        parserVersion = snapshot.parserVersion))
                    retryAt.remove(account.id)
                }
                is ProviderResult.Failure -> {
                    val code = if (result.message == "FOREGROUND_REQUIRED") "FOREGROUND_REQUIRED" else result.code.name
                    persist(account.copy(lastAttemptAt = started, lastErrorCode = code))
                    dao.putLog(SyncLogEntity(accountId = account.id, startedAt = started, resultCode = code))
                    result.retryAfterMillis?.let { retryAt[account.id] = started + it.coerceIn(0, 86_400_000) }
                }
            }
            dao.pruneLogs()
        }
    }

    suspend fun logout(id: String) = locks.getOrPut(id) { Mutex() }.withLock {
        val account = account(id) ?: return@withLock
        credentials.deleteSecret(id)
        account.profileName?.let { withContext(Dispatchers.Main) { ProfileSessions.delete(it) } }
        WorkManager.getInstance(context).cancelUniqueWork("sync_$id")
        persist(account.copy(lastErrorCode = "AUTH_REQUIRED"))
        updateWidgets(context)
    }

    suspend fun deleteAccount(id: String) = lifecycleLock.withLock {
        deleteAccountUnlocked(id)
        updateWidgets(context)
    }
    suspend fun clearCredentials(webOnly: Boolean = false, apiOnly: Boolean = false) {
        dao.accounts().map { json.decodeFromString<Account>(it.payload) }.filter {
            (!webOnly || it.authMode == AuthMode.WEB_PROFILE) && (!apiOnly || it.authMode == AuthMode.API_KEY)
        }.forEach { logout(it.id) }
    }
    suspend fun clearData() = lifecycleLock.withLock {
        dao.accounts().forEach { deleteAccountUnlocked(it.id) }
        credentials.deleteAll()
        withContext(Dispatchers.Main) { if (ProfileSessions.supported()) ProfileSessions.deleteAll() }
        WorkManager.getInstance(context).cancelAllWork()
        withContext(Dispatchers.IO) { database.clearAllTables() }
        settings.reset()
        updateWidgets(context)
    }
    private suspend fun deleteAccountUnlocked(id: String) {
        locks.getOrPut(id) { Mutex() }.withLock {
            val account = account(id) ?: return@withLock
            persist(account.copy(enabled = false, lastErrorCode = "CLEANUP_PENDING"))
            WorkManager.getInstance(context).cancelUniqueWork("sync_$id")
            credentials.deleteSecret(id)
            val profileRemoved = account.profileName == null || runCatching {
                withContext(Dispatchers.Main) { ProfileSessions.delete(account.profileName) }
            }.getOrDefault(false)
            if (!profileRemoved) return@withLock
            database.withTransaction { dao.deleteAccount(id) }
            retryAt.remove(id)
        }
    }
    suspend fun cleanup() {
        val retention = settings.current().retentionDays
        if (retention > 0) dao.pruneSnapshots(System.currentTimeMillis() - retention * 86_400_000L)
        dao.pruneLogs()
        val manager = android.appwidget.AppWidgetManager.getInstance(context)
        val active = manager.getAppWidgetIds(android.content.ComponentName(context, com.eslee.llmusage.widget.UsageWidgetReceiver::class.java)).toSet()
        dao.widgets().filter { it.appWidgetId !in active }.forEach { dao.deleteWidget(it.appWidgetId) }
    }
    suspend fun saveWidget(id: Int, json: String, accountIds: List<String>) = database.withTransaction {
        dao.putWidget(WidgetEntity(id, json))
        dao.clearWidgetAccounts(id)
        dao.putWidgetAccounts(accountIds.distinct().filter { dao.account(it) != null }.mapIndexed { position, accountId -> WidgetAccountEntity(id, accountId, position) })
    }
    suspend fun widgetJson(id: Int) = dao.widget(id)?.payload
    suspend fun widgetConfigs() = dao.widgets().map { it.appWidgetId to it.payload }
    suspend fun deleteWidget(id: Int) = dao.deleteWidget(id)
    suspend fun widgetAccounts(id: Int) = dao.widgetAccounts(id)
}
