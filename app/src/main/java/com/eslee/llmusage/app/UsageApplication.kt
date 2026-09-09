package com.eslee.llmusage.app

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.eslee.llmusage.BuildConfig
import com.eslee.llmusage.core.database.UsageDatabase
import com.eslee.llmusage.core.security.CredentialStore
import com.eslee.llmusage.provider.ProviderRegistry
import com.eslee.llmusage.settings.SettingsStore
import com.eslee.llmusage.sync.SyncScheduler
import com.eslee.llmusage.usage.UsageRepository
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton

@HiltAndroidApp
class UsageApplication : Application() {
    @Inject lateinit var graph: AppGraph
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onCreate() {
        super.onCreate()
        scope.launch { graph.settings.flow.collect { SyncScheduler.schedule(this@UsageApplication, it) } }
    }
}
@Singleton
class AppGraph @Inject constructor(@ApplicationContext context: Context) {
    val database = Room.databaseBuilder(context, UsageDatabase::class.java, "usage.db").build()
    val registry = ProviderRegistry(BuildConfig.DEBUG)
    val credentials = CredentialStore(context)
    val settings = SettingsStore(context)
    val repository = UsageRepository(context, database, registry, credentials, settings)
}
