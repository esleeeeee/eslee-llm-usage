# App integration contract

All classes single app module package `com.eslee.llmusage`.

`app.UsageApplication : Application` exposes `val graph: AppGraph`.
`app.AppGraph` exposes `repository: usage.UsageRepository`, `settings: settings.SettingsStore`, `registry: provider.ProviderRegistry`, `credentials: core.security.CredentialStore`.

Repository interface (suspend unless stated):
- `val accounts: Flow<List<AccountOverview>>` (not suspend)
- `suspend fun account(id: String): Account?`
- `suspend fun latest(id: String): UsageSnapshot?`
- `suspend fun history(id: String): List<UsageSnapshot>`
- `suspend fun logs(id: String? = null): List<SyncLog>`
- `suspend fun addAccount(providerId: String, alias: String, secret: String? = null, teamId: String? = null): String` creates UUID and returns id, does not auto launch web
- `suspend fun updateAccount(account: Account)`
- `suspend fun setCredential(id: String, secret: String)`
- `suspend fun refresh(id: String)` official/demo; foreground-only marks needing foreground
- `suspend fun refreshAll()` background capable only
- `suspend fun recordWeb(id: String, text: String)` parse and save success or failure retaining cache
- `suspend fun logout(id: String)`
- `suspend fun deleteAccount(id: String)`
- `suspend fun clearData()` all local data + profiles + credentials + widgets + work + settings
- `suspend fun clearCredentials(webOnly: Boolean = false, apiOnly: Boolean = false)`

`usage.AccountOverview(val account: Account, val snapshot: UsageSnapshot?)`
`usage.SyncLog(val accountId:String,val startedAt:Long,val resultCode:String,val parserVersion:String?=null)`

`settings.AppSettings` serializable data class:
`intervalMinutes:Long=60, wifiOnly:Boolean=false, staleHours:Int=0` (0=provider default), `retentionDays:Int=30` (0=unlimited), `theme:String="SYSTEM"`, `remaining:Boolean=true`, `widgetTheme:String="SYSTEM"`.
`SettingsStore.val flow: Flow<AppSettings>`, `suspend fun current(): AppSettings`, `suspend fun update(value:AppSettings)`, `suspend fun reset()`.

Widget agent owns serializable `widget.WidgetConfig` and config DAO facade `widget.WidgetConfigStore`. It must use Room: root provides `graph.repository.saveWidget(id:Int,json:String,accountIds:List<String>)`, `widgetJson(id:Int):String?`, `widgetConfigs():List<Pair<Int,String>>`, `deleteWidget(id:Int)` and `widgetAccounts(id:Int):List<String>`. Config JSON + explicit account relation. Widget implementation helper `suspend fun updateWidgets(context:Context)` callable root after sync. Config is parsed by widget agent.

Web agent exposes `core.web.ProfileSessions.supported():Boolean`, `delete(profileName:String)` on main thread, `deleteAll()` on main thread. Activities receive extra `"accountId"`. Main activity `ui.MainActivity`, detail navigation via same extra. Root handles ProfileSessions deletion with Dispatchers.Main.

Do not assume exact Provider/model field contracts until providers agent finalizes docs/PROVIDER_CONTRACT.md; discuss directly.
