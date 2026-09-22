package com.eslee.llmusage.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.eslee.llmusage.R
import com.eslee.llmusage.core.model.*
import com.eslee.llmusage.provider.ConnectorStatus
import com.eslee.llmusage.ui.gauge.UsageGauge
import com.eslee.llmusage.usage.AccountOverview
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import kotlin.math.roundToInt

internal fun timeLabel(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
internal fun clockLabel(value: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(value))

internal enum class Severity { OK, WARN, ERROR }
internal data class StatusInfo(val text: String, val severity: Severity) {
    val warning: Boolean get() = severity != Severity.OK
}

internal fun staleAfter(snapshot: UsageSnapshot, staleHours: Int): Long =
    if (staleHours > 0) staleHours * 3_600_000L else if (snapshot.source == SnapshotSource.VISIBLE_PAGE) 21_600_000L else 7_200_000L

@Composable
internal fun statusInfo(account: Account, snapshot: UsageSnapshot?, staleHours: Int): StatusInfo {
    val (resource, severity) = when {
        account.lastErrorCode == "CLEANUP_PENDING" -> R.string.cleanup_pending to Severity.ERROR
        !account.enabled -> R.string.disabled to Severity.WARN
        account.lastErrorCode in setOf("AUTH_REQUIRED", "PERMISSION_DENIED") -> R.string.needs_auth to Severity.ERROR
        account.lastErrorCode in setOf("PARSE_FAILED", "PARSER_OUTDATED", "INVALID_RESPONSE") -> R.string.parse_failed to Severity.ERROR
        account.lastErrorCode == "RATE_LIMITED" -> R.string.rate_limited to Severity.WARN
        account.lastErrorCode in setOf("NETWORK", "NETWORK_TIMEOUT") -> R.string.network_error to Severity.WARN
        account.lastErrorCode == "NEEDS_VALIDATION" -> R.string.needs_validation to Severity.WARN
        snapshot == null -> R.string.needs_validation to Severity.WARN
        UsagePresentation.isStale(snapshot, staleAfterMillis = staleAfter(snapshot, staleHours)) -> R.string.stale to Severity.WARN
        else -> R.string.normal to Severity.OK
    }
    return StatusInfo(stringResource(resource), severity)
}

@Composable
internal fun statusLabel(account: Account, snapshot: UsageSnapshot?, staleHours: Int): String = statusInfo(account, snapshot, staleHours).text

@Composable
internal fun connectorLabel(status: ConnectorStatus): String = stringResource(when (status) {
    ConnectorStatus.STABLE_OFFICIAL -> R.string.official
    ConnectorStatus.OFFICIAL_UNVERIFIED -> R.string.official_unverified
    ConnectorStatus.EXPERIMENTAL_WEB -> R.string.experimental
    ConnectorStatus.PLACEHOLDER_UNSUPPORTED -> R.string.unsupported
    ConnectorStatus.DISABLED_POLICY -> R.string.policy_disabled
})

@Composable
internal fun confidenceLabel(confidence: Confidence): String = stringResource(when (confidence) {
    Confidence.EXACT -> R.string.exact
    Confidence.REPORTED -> R.string.reported
    Confidence.DERIVED -> R.string.derived
    Confidence.STALE -> R.string.stale
    Confidence.UNKNOWN -> R.string.unknown
})

/** One top bar for every screen, so back, titles and actions sit in the same place everywhere. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScreenScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    busy: Boolean = false,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                    actions = actions,
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        floatingActionButton = floatingActionButton,
        snackbarHost = snackbarHost,
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

@Composable
internal fun SectionTitle(resource: Int, modifier: Modifier = Modifier) {
    Text(stringResource(resource), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
internal fun ProviderMark(providerId: String?, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Surface(modifier.size(size), shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(ProviderMarks.icon(providerId)), contentDescription = null, modifier = Modifier.size(size / 2), tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
internal fun StatusChip(info: StatusInfo, modifier: Modifier = Modifier) {
    val (container, content) = when (info.severity) {
        Severity.OK -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        Severity.WARN -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        Severity.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Surface(modifier, shape = MaterialTheme.shapes.small, color = container, contentColor = content) {
        Text(info.text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Ring, short window name and countdown: the same three things the widget shows for a quota. */
@Composable
internal fun BucketGauge(bucket: UsageBucket, providerId: String?, modifier: Modifier = Modifier, size: Dp = 80.dp, warning: Boolean = false) {
    val context = LocalContext.current
    val remaining = UsageNormalizer.remainingPercent(bucket)
    Column(modifier.width(size + 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        UsageGauge(remaining, painterResource(ProviderMarks.icon(providerId)), size = size, warning = warning)
        Text(UsageLabels.bucketShort(context, bucket), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            UsageLabels.resetCompact(context, bucket.resetAt) ?: if (remaining == null) stringResource(R.string.unknown) else "",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        )
    }
}

/** A quota in words: the share left first, its complement after it, then the reset. */
@Composable
internal fun BucketContent(bucket: UsageBucket, detailed: Boolean = false) {
    val context = LocalContext.current
    val normalized = UsageNormalizer.normalize(bucket)
    val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(bucket.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val remaining = normalized.remainingPercent
        val used = normalized.usedPercent
        Text(
            when {
                remaining != null -> stringResource(R.string.remaining_percent, remaining.roundToInt()) +
                    (used?.let { " · " + stringResource(R.string.used_percent, it.roundToInt()) } ?: "")
                used != null -> stringResource(R.string.used_percent, used.roundToInt())
                normalized.remaining != null -> "${unitPrefix(bucket.unit)}${number.format(normalized.remaining)} ${stringResource(R.string.remaining)}"
                normalized.used != null -> "${unitPrefix(bucket.unit)}${number.format(normalized.used)} ${stringResource(R.string.used)}"
                else -> stringResource(R.string.unknown)
            },
            style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
        )
        Text(
            bucket.resetAt?.let { "${UsageLabels.resetRelative(context, it)} · ${UsageLabels.resetAbsolute(it)}" } ?: stringResource(R.string.reset_unknown),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (detailed) {
            if (normalized.used != null && normalized.limit != null) Text("${number.format(normalized.used)} / ${number.format(normalized.limit)} ${bucket.unit.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            bucket.modelOrFeature?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(stringResource(R.string.confidence, confidenceLabel(bucket.confidence)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun unitPrefix(unit: UsageUnit): String = if (unit == UsageUnit.USD) "$" else ""

/** Buckets worth a ring: those with a percentage, in the account's preferred order. */
internal fun gaugeBuckets(snapshot: UsageSnapshot?, primaryId: String?): List<UsageBucket> {
    if (snapshot == null) return emptyList()
    val primary = UsagePresentation.primary(snapshot, primaryId)
    return snapshot.buckets.filter { UsageNormalizer.remainingPercent(it) != null || UsageNormalizer.usedPercent(it) != null }
        .sortedBy { if (it.id == primary?.id) 0 else 1 }
}

@Composable
internal fun AccountCard(overview: AccountOverview, providerName: String, staleHours: Int, onOpen: () -> Unit) {
    val account = overview.account
    val snapshot = overview.snapshot
    val status = statusInfo(account, snapshot, staleHours)
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProviderMark(account.providerId)
                Column(Modifier.weight(1f)) {
                    Text(account.alias, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(providerName, snapshot?.planName).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                StatusChip(status)
            }
            val gauges = gaugeBuckets(snapshot, account.primaryBucketId)
            if (gauges.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    gauges.forEach { BucketGauge(it, account.providerId, warning = status.warning) }
                }
            } else if (snapshot != null && snapshot.buckets.isNotEmpty()) {
                snapshot.buckets.take(3).forEach { BucketContent(it) }
            } else {
                Text(stringResource(R.string.no_snapshot), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    snapshot?.extraCredits?.let { stringResource(R.string.credits_value, creditLabel(it)) } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    stringResource(R.string.synced_at, account.lastSuccessAt?.let(::clockLabel) ?: stringResource(R.string.never)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun creditLabel(credit: CreditBalance): String {
    val amount = NumberFormat.getNumberInstance().apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }.format(credit.amount)
    return if (credit.currency == "USD") "$$amount" else "${credit.currency} $amount"
}

@Composable
internal fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable ColumnScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
            listOf("chatgpt", "claude", "grok").forEach { ProviderMark(it, size = 44.dp) }
        }
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        action()
    }
}

@Preview(name = "Card · dark", widthDp = 360, showBackground = true, backgroundColor = 0xFF0E1013)
@Composable
private fun CardPreview() = UsageTheme(darkTheme = true) {
    AccountCard(
        AccountOverview(
            Account("1", "chatgpt", "Plus", AuthMode.WEB_PROFILE, lastSuccessAt = System.currentTimeMillis()),
            UsageSnapshot("1", "chatgpt", listOf(
                UsageBucket("session", "5-hour usage", remainingPercent = 76.0, resetAt = System.currentTimeMillis() + 3 * 3_600_000),
                UsageBucket("weekly", "Weekly usage", remainingPercent = 24.0, resetAt = System.currentTimeMillis() + 2 * 86_400_000),
            ), planName = "ChatGPT Plus", extraCredits = CreditBalance(12.3)),
        ),
        "Codex", 0, {},
    )
}

@Preview(name = "Card · unknown light", widthDp = 320, fontScale = 1.3f, showBackground = true)
@Composable
private fun UnknownPreview() = UsageTheme(darkTheme = false) {
    AccountCard(
        AccountOverview(Account("1", "claude", "Very long account alias with thirty characters", AuthMode.WEB_PROFILE, lastErrorCode = "AUTH_REQUIRED"),
            UsageSnapshot("1", "claude", listOf(UsageBucket("weekly", "Weekly usage", confidence = Confidence.UNKNOWN)))),
        "Claude", 0, {},
    )
}
