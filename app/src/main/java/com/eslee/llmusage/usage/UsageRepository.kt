package com.eslee.llmusage.usage

import android.content.Context
import androidx.room.withTransaction
import androidx.work.WorkManager
import com.eslee.llmusage.core.database.*
import com.eslee.llmusage.core.model.*
import com.eslee.llmusage.core.security.CredentialStore
import com.eslee.llmusage.core.web.ProfileSessions
import com.eslee.llmusage.core.web.WebUsageReader
import com.eslee.llmusage.provider.*
import com.eslee.llmusage.settings.SettingsStore
import com.eslee.llmusage.widget.WidgetConfig
import com.eslee.llmusage.widget.WidgetSelection
import com.eslee.llmusage.widget.installedWidgetIds
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
    private val consumerFetch: (suspend (Account, ProviderDefinition) -> ProviderResult)? = null,
) {
    private val webReader = WebUsageReader(context)
    private val dao = database.dao()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lifecycleLock = Mutex()
    private val retryAt = ConcurrentHashMap<String, Long>()
    private val activeRefreshes = ConcurrentHashMap<String, Deferred<Unit>>()
    private val sessionResetLocks = ConcurrentHashMap<String, Mutex>()
    private val resettingSessions = ConcurrentHashMap.newKeySet<String>()
    private class SessionResetCancellation : CancellationException("Account session reset")
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

    /** Read, change and store an account in one transaction, so edits of different fields never undo each other. */
    private suspend fun modifyAccount(id: String, change: (Account) -> Account): Account? = database.withTransaction {
        val current = account(id) ?: return@withTransaction null
        change(current).also { persist(it) }
    }

    /**
     * Not behind the account's collection lock: a rename or a new main quota used to
     * wait for a page load that could take a minute, and looked as if it did nothing.
     * A collection re-reads the account when it saves, so neither write undoes the other.
     */
    suspend fun updateAccount(account: Account) {
        modifyAccount(account.id) { existing ->
            existing.copy(alias = account.alias.trim().take(80).ifEmpty { existing.alias }, enabled = account.enabled,
                primaryBucketId = account.primaryBucketId)
        }
        updateWidgets(context)
    }
    suspend fun setCredential(id: String, secret: String) = locks.getOrPut(id) { Mutex() }.withLock {
        val account = account(id) ?: return@withLock
        require(account.authMode == AuthMode.API_KEY && secret.isNotBlank())
        val bytes = secret.toByteArray()
        try { credentials.putSecret(id, bytes) } finally { bytes.fill(0) }
        modifyAccount(id) { it.copy(lastErrorCode = "NEEDS_VALIDATION") }
    }

    suspend fun refresh(id: String) {
        if (id in resettingSessions) return
        val mutex = locks.getOrPut(id) { Mutex() }
        // Already being collected -- by the schedule, the open app or the widget.
        // Returning at once used to end the caller's refresh with nothing new to
        // show; wait for that answer instead, and try again only if it failed.
        val waited = !mutex.tryLock()
        if (waited) mutex.lock()
        try {
            if (waited && account(id)?.lastErrorCode == null) return
            supervisorScope {
                val collection = async(start = CoroutineStart.LAZY) {
                    if (id in resettingSessions) throw SessionResetCancellation()
                    collectRefresh(id)
                }
                activeRefreshes[id] = collection
                try {
                    collection.await()
                } catch (reset: SessionResetCancellation) {
                    // Logout stops only this account, while sync_all continues. Never
                    // consume cancellation of the worker or its caller.
                    currentCoroutineContext().ensureActive()
                } finally {
                    activeRefreshes.remove(id, collection)
                }
            }
        } finally { mutex.unlock() }
        updateWidgets(context)
    }

    private suspend fun collectRefresh(id: String) {
        val account = account(id)?.takeIf { it.enabled } ?: return
        if ((retryAt[id] ?: 0) > System.currentTimeMillis()) return
        val started = System.currentTimeMillis()
        val result = try {
            if (account.authMode == AuthMode.WEB_PROFILE) {
                val provider = requireNotNull(registry.definition(account.providerId))
                consumerFetch?.invoke(account, provider) ?: webReader.fetch(account, provider)
            } else if (registry.definition(account.providerId)?.capabilities?.supportsBackgroundSync != true) {
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
    }

    /** Collects every enabled account and returns how many of them failed. */
    suspend fun refreshAll(webOnly: Boolean? = null): Int {
        val selected = dao.accounts().map { json.decodeFromString<Account>(it.payload) }
            .filter { it.enabled && registry.definition(it.providerId)?.capabilities?.supportsBackgroundSync == true }
            .filter { webOnly == null || (it.authMode == AuthMode.WEB_PROFILE) == webOnly }
        refreshAccountsIndependently(selected) { refresh(it.id) }
        cleanup()
        return selected.count { account(it.id)?.let { current -> current.enabled && current.lastErrorCode != null } == true }
    }

    /**
     * Stores what the sign-in window read. Not behind the collection lock either:
     * the read is already done, and waiting for a background load of the same
     * account made the read button look stuck.
     *
     * A read the user did not ask for -- the window reads every page it passes --
     * records only a success, so browsing the chat never marks the account failing.
     */
    suspend fun recordWeb(id: String, text: String, richText: String? = null, recordFailure: Boolean = true): ProviderResult {
        val account = account(id)?.takeIf { it.enabled && it.authMode == AuthMode.WEB_PROFILE }
            ?: return ProviderResult.Failure(ProviderErrorCode.CONFIGURATION, "계정을 사용할 수 없습니다.")
        val started = System.currentTimeMillis()
        val parsed = withContext(Dispatchers.Default) { ConsumerUsageParser.parseBest(account.providerId, id, text, richText, started) }
        // A page that has not drawn its main quota must not replace the last good reading.
        val result = if (parsed is ProviderResult.Success && !ConsumerUsageParser.primaryHasNumbers(parsed)) {
            ProviderResult.Failure(ProviderErrorCode.PARSE_FAILED, "대표 항목의 수치를 찾지 못했습니다.")
        } else parsed
        if (result is ProviderResult.Success || recordFailure) {
            saveResult(account, result, started)
            updateWidgets(context)
        }
        return result
    }

    private suspend fun saveResult(account: Account, result: ProviderResult, started: Long) {
        database.withTransaction {
            // Re-read rather than write back the copy taken when collection began: a
            // rename or a new main quota chosen during a long load must survive it.
            val current = this@UsageRepository.account(account.id) ?: return@withTransaction
            when (result) {
                is ProviderResult.Success -> {
                    val snapshot = result.snapshot.copy(buckets = result.snapshot.buckets.map(UsageNormalizer::normalize))
                    dao.putSnapshot(SnapshotEntity(snapshot.snapshotId, account.id, snapshot.fetchedAt, json.encodeToString(snapshot)))
                    dao.putBuckets(snapshot.buckets.map { BucketEntity(UUID.randomUUID().toString(), snapshot.snapshotId, it.id, json.encodeToString(it)) })
                    snapshot.extraCredits?.let { dao.putCredit(CreditEntity(snapshot.snapshotId, it.amount, it.currency)) }
                    persist(current.copy(lastAttemptAt = started, lastSuccessAt = snapshot.fetchedAt, lastErrorCode = null))
                    dao.putLog(SyncLogEntity(accountId = account.id, startedAt = started, resultCode = "SUCCESS",
                        parserVersion = snapshot.parserVersion))
                    retryAt.remove(account.id)
                }
                is ProviderResult.Failure -> {
                    val code = if (result.message == "FOREGROUND_REQUIRED") "FOREGROUND_REQUIRED" else result.code.name
                    persist(current.copy(lastAttemptAt = started, lastErrorCode = code))
                    dao.putLog(SyncLogEntity(accountId = account.id, startedAt = started, resultCode = code))
                    result.retryAfterMillis?.let { retryAt[account.id] = started + it.coerceIn(0, 86_400_000) }
                }
            }
            dao.pruneLogs()
        }
    }

    suspend fun logout(id: String) = sessionResetLocks.getOrPut(id) { Mutex() }.withLock {
        resettingSessions.add(id)
        try {
            activeRefreshes[id]?.cancel(SessionResetCancellation())
            locks.getOrPut(id) { Mutex() }.withLock { logoutLocked(id) }
        } finally {
            resettingSessions.remove(id)
        }
    }

    private suspend fun logoutLocked(id: String) {
        val account = account(id) ?: return
        credentials.deleteSecret(id)
        account.profileName?.let { withContext(Dispatchers.Main) { ProfileSessions.delete(it) } }
        WorkManager.getInstance(context).cancelUniqueWork("sync_$id")
        val freshProfile = account.profileName?.let { ProfileSessions.name(account.providerId, UUID.randomUUID().toString()) }
        modifyAccount(id) { it.copy(lastErrorCode = "AUTH_REQUIRED", profileName = freshProfile) }
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
            WorkManager.getInstance(context).cancelUniqueWork("sync_$id")
            credentials.deleteSecret(id)
            account.profileName?.let { name ->
                withContext(Dispatchers.Main) { ProfileSessions.delete(name) }
            }
            pruneWidgetsForAccount(id)
            database.withTransaction { dao.deleteAccount(id) }
            retryAt.remove(id)
        }
    }

    private suspend fun pruneWidgetsForAccount(accountId: String) {
        dao.widgets().forEach { widget ->
            val remainingIds = dao.widgetAccounts(widget.appWidgetId).filter { it != accountId }
            val parsed = runCatching { json.decodeFromString<WidgetConfig>(widget.payload) }.getOrNull()
            val selections = (parsed?.selections?.filter { it.accountId != accountId }
                ?: remainingIds.map { WidgetSelection(it) }).ifEmpty { remainingIds.map { WidgetSelection(it) } }
            if (selections.isEmpty()) {
                dao.deleteWidget(widget.appWidgetId)
            } else {
                val pruned = (parsed ?: WidgetConfig(widget.appWidgetId)).copy(selections = selections)
                dao.putWidget(widget.copy(payload = json.encodeToString(pruned)))
                dao.clearWidgetAccounts(widget.appWidgetId)
                dao.putWidgetAccounts(pruned.selections.mapIndexed { position, selection ->
                    WidgetAccountEntity(widget.appWidgetId, selection.accountId, position)
                })
            }
        }
    }
    suspend fun cleanup() {
        val retention = settings.current().retentionDays
        if (retention > 0) dao.pruneSnapshots(System.currentTimeMillis() - retention * 86_400_000L)
        dao.pruneLogs()
        // Every widget kind shares this store. Counting only the ring widget deleted the
        // reset-credit widget's accounts after each refresh.
        val active = installedWidgetIds(context)
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

/** Storage errors are reported only after every account has had a chance to finish. */
internal suspend fun <T> refreshAccountsIndependently(accounts: List<T>, refresh: suspend (T) -> Unit) = supervisorScope {
    val failures = accounts.map { account ->
        async {
            try { refresh(account); null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { error }
        }
    }.awaitAll()
    failures.firstOrNull { it != null }?.let { throw it }
    Unit
}
