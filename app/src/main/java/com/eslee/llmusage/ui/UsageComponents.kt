package com.eslee.llmusage.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.eslee.llmusage.R
import com.eslee.llmusage.core.model.*
import com.eslee.llmusage.provider.ConnectorStatus
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

internal fun timeLabel(value: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))

@Composable
internal fun statusLabel(account: Account, snapshot: UsageSnapshot?, staleHours: Int): String = stringResource(when {
    account.lastErrorCode == "CLEANUP_PENDING" -> R.string.cleanup_pending
    !account.enabled -> R.string.disabled
    account.lastErrorCode in setOf("AUTH_REQUIRED", "PERMISSION_DENIED") -> R.string.needs_auth
    account.lastErrorCode in setOf("PARSE_FAILED", "PARSER_OUTDATED", "INVALID_RESPONSE") -> R.string.parse_failed
    account.lastErrorCode == "RATE_LIMITED" -> R.string.rate_limited
    account.lastErrorCode in setOf("NETWORK", "NETWORK_TIMEOUT") -> R.string.network_error
    account.lastErrorCode == "NEEDS_VALIDATION" -> R.string.needs_validation
    snapshot == null -> R.string.needs_validation
    UsagePresentation.isStale(snapshot, staleAfterMillis = if (staleHours > 0) staleHours * 3_600_000L else if (snapshot.source == SnapshotSource.VISIBLE_PAGE) 21_600_000L else 7_200_000L) -> R.string.stale
    else -> R.string.normal
})

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

@Composable
internal fun BucketContent(bucket: UsageBucket, remaining: Boolean, detailed: Boolean = false) {
    val normalized = UsageNormalizer.normalize(bucket)
    val percent = if (remaining) normalized.remainingPercent else normalized.usedPercent
    val raw = if (remaining) normalized.remaining else normalized.used
    val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(bucket.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Text(when { percent != null -> "${number.format(percent)}%"; raw != null -> "${if (bucket.unit == UsageUnit.USD) "$" else ""}${number.format(raw)}"; else -> "—" },
                style = MaterialTheme.typography.headlineLarge)
            Text(if (percent == null && raw == null) stringResource(R.string.unknown) else stringResource(if (remaining) R.string.remaining else R.string.used),
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 5.dp))
        }
        // Battery-style gauge: always the capacity left, full when unused, whichever number the text shows.
        normalized.remainingPercent?.let { left -> LinearProgressIndicator(progress = { (left / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
        bucket.resetAt?.let { Text(stringResource(R.string.reset_at, timeLabel(it)), style = MaterialTheme.typography.bodySmall) }
        if (detailed) {
            if (normalized.used != null && normalized.limit != null) Text("${number.format(normalized.used)} / ${number.format(normalized.limit)} ${bucket.unit.name}", style = MaterialTheme.typography.bodySmall)
            bucket.modelOrFeature?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(stringResource(R.string.confidence, confidenceLabel(bucket.confidence)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun AccountCard(account: Account, snapshot: UsageSnapshot?, provider: String, remaining: Boolean, staleHours: Int, onOpen: () -> Unit, onRefresh: () -> Unit) {
    OutlinedCard(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(provider.take(1), modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
                }
                Column(Modifier.weight(1f)) {
                    Text(account.alias, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(provider, snapshot?.planName).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
            val primary = snapshot?.let { UsagePresentation.primary(it, account.primaryBucketId) }
            if (primary != null) BucketContent(primary, remaining) else Text(stringResource(R.string.no_snapshot))
            Text(statusLabel(account, snapshot, staleHours), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.last_sync, account.lastSuccessAt?.let(::timeLabel) ?: stringResource(R.string.never)), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onRefresh, enabled = account.enabled) { Text(stringResource(R.string.refresh)) }
        }
    }
}

@Preview(name = "Narrow unknown · 1.3 font", widthDp = 320, fontScale = 1.3f, showBackground = true)
@Composable
private fun UnknownPreview() = UsageTheme {
    AccountCard(Account("1", "claude", "Very long account alias with thirty characters", AuthMode.WEB_PROFILE),
        UsageSnapshot("1", "claude", listOf(UsageBucket("unknown", "Long usage bucket label with unknown quota", confidence = Confidence.UNKNOWN))),
        "Claude", true, 0, {}, {})
}

@Preview(name = "Dark normal", widthDp = 360, showBackground = true)
@Composable
private fun DarkPreview() = UsageTheme(darkTheme = true) {
    AccountCard(Account("1", "demo", "Demo", AuthMode.NONE, lastSuccessAt = System.currentTimeMillis()),
        UsageSnapshot("1", "demo", listOf(UsageBucket("weekly", "Weekly", usedPercent = 42.0, resetAt = System.currentTimeMillis() + 86_400_000))),
        "Demo", false, 0, {}, {})
}
