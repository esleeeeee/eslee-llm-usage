package com.eslee.llmusage.ui

import androidx.annotation.DrawableRes
import com.eslee.llmusage.R

/**
 * Marks are the app's own abstract glyphs, one per service family, so a ring
 * can be told apart at a glance without shipping any provider's logo.
 */
object ProviderMarks {
    @DrawableRes
    fun icon(providerId: String?): Int = when (providerId) {
        "chatgpt" -> R.drawable.ic_mark_codex
        "claude" -> R.drawable.ic_mark_claude
        "grok" -> R.drawable.ic_mark_grok
        else -> R.drawable.ic_mark_api
    }
}
