# Provider contract

Packages `com.eslee.llmusage.core.model` and `com.eslee.llmusage.provider`. All times are epoch milliseconds (`Long`).

- `AuthMode`: `API_KEY`, `WEB_PROFILE`, `OAUTH_PKCE`, `NONE`.
- `Account(id, providerId, alias, authMode, profileName: String? = null, teamId: String? = null, enabled: Boolean = true, primaryBucketId: String? = null, createdAt: Long = System.currentTimeMillis(), lastSuccessAt: Long? = null, lastAttemptAt: Long? = null, lastErrorCode: String? = null)`.
- `UsageBucket(id: String, label: String, used: Double? = null, limit: Double? = null, remaining: Double? = null, unit: UsageUnit = REQUESTS, resetAt: Long? = null, windowLabel: String? = null)`; nullable values never imply zero. `UsageUnit`: `REQUESTS`, `TOKENS`, `USD`, `PERCENT`.
- `UsageSnapshot(accountId: String, providerId: String, buckets: List<UsageBucket>, fetchedAt: Long = now, source: SnapshotSource = OFFICIAL_API, note: String? = null)`; `SnapshotSource`: `OFFICIAL_API`, `VISIBLE_PAGE`, `DEMO`.
- `ProviderDefinition(id, displayName, authMode, supported: Boolean, description: String, validation: String)`.
- `ProviderRegistry(debug: Boolean)` exposes `definitions: List<ProviderDefinition>`, `definition(id): ProviderDefinition?`, `adapter(id): UsageProvider?`.
- `UsageProvider`: `suspend fun fetch(account: Account, secret: String): ProviderResult`.
- `ProviderResult`: `Success(snapshot: UsageSnapshot)` or `Failure(code: ProviderErrorCode, message: String, retryAfterMillis: Long? = null)`; codes `AUTH_REQUIRED`, `PERMISSION_DENIED`, `RATE_LIMITED`, `NETWORK`, `INVALID_RESPONSE`, `UNSUPPORTED`, `CONFIGURATION`.
- `UsageNormalizer.normalize(bucket): UsageBucket`, `UsageNormalizer.remainingPercent(bucket): Double?`.
- `UsagePresentation.primary(snapshot, preferredBucketId: String? = null): UsageBucket?`, `isStale(snapshot, now: Long, staleAfterMillis: Long): Boolean`, `countdown(resetAt: Long?, now: Long): Long?` returns nonnegative milliseconds remaining.
- Consumer visible text import: `ConsumerUsageParser.parse(providerId, accountId, visibleText, fetchedAt = now): ProviderResult`. Does not fetch private endpoints or inspect browser credentials.

Official HTTP adapters require privileged usage/reporting credentials. Endpoint schema verified against primary docs; live credential integration is not yet validated. Unsupported consumer providers return explicit unsupported status.


## Final domain additions

`UsageBucket` additionally has nullable `usedPercent`, `remainingPercent`, `modelOrFeature`, plus `confidence` and `scope`. `UsageSnapshot` includes `snapshotId`, `planName`, `accountLabel`, `extraCredits`, `validUntil`, `confidence`, `syncMode`, `status`, `primaryBucketId`, and `parserVersion`. All additions have defaults. `UsageNormalizer.usedPercent` and `UsagePresentation.formatCountdown` are available.

`ProviderDefinition` includes `status`, `capabilities`, nullable `loginUrl`/`usageUrl`, and `allowedHosts: Set<String>`. Official adapters are explicitly `OFFICIAL_UNVERIFIED` until live credential validation. `STABLE_OFFICIAL` is reserved, not displayed for current adapters.

Errors additionally distinguish `NETWORK_TIMEOUT`, `PARSE_FAILED`, and `PARSER_OUTDATED`. Consumer zero-bucket output is failure; reset-only known surface is a partial snapshot with unknown usage. Plain local timestamps without an explicit timezone are not guessed. Only ISO offsets or unambiguous relative durations are parsed.

## Endpoint verification (2026-09-09)

- OpenAI: https://developers.openai.com/api/reference/resources/admin/subresources/organization/subresources/usage — completions usage, organization Admin key, cursor pagination.
- Anthropic: https://platform.claude.com/docs/en/api/admin/usage_report/retrieve_messages — organization messages report, Admin key, cursor pagination.
- xAI: https://docs.x.ai/developers/rest-api-reference/management/billing — POST team usage analytics, Management key, explicit `limitReached` rejection.

Adapters request today's UTC window. These are API activity totals, not consumer plan quotas. Missing quota and reset fields stay null. Server response text is never included in errors, response size is bounded, redirects are disabled, and coroutine cancellation cancels the HTTP call. Tests use synthetic data and local MockWebServer; no real secrets or personal webpage contents are included.
