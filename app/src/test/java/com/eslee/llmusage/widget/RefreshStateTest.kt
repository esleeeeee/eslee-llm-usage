package com.eslee.llmusage.widget

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The refresh button reads its colour from widget state: amber while a pass
 * runs, green for a moment after it, quiet otherwise. The finish pass removes
 * the green mark itself because Glance only redraws on a state change; the
 * time guard exists for a process killed before it could.
 */
class RefreshStateTest {
    private val refreshing = booleanPreferencesKey("refreshing")
    private val refreshedAt = longPreferencesKey("refreshed_at")
    private val now = 1_700_000_000_000L

    @Test fun aRunningPassIsAmberWhateverElseIsStored() {
        assertEquals(RefreshState.RUNNING, refreshState(preferencesOf(refreshing to true, refreshedAt to now), now))
    }

    @Test fun aFreshlyFinishedPassIsGreen() {
        assertEquals(RefreshState.DONE, refreshState(preferencesOf(refreshedAt to now), now))
        assertEquals(RefreshState.DONE, refreshState(preferencesOf(refreshedAt to now - REFRESH_DONE_MILLIS + 1), now))
    }

    @Test fun removingTheMarkIsWhatMakesTheButtonQuiet() {
        assertEquals(RefreshState.IDLE, refreshState(preferencesOf(), now))
        assertEquals(RefreshState.IDLE, refreshState(preferencesOf(refreshing to false), now))
    }

    @Test fun aMarkLeftBehindByAKilledProcessExpiresOnItsOwn() {
        assertEquals(RefreshState.IDLE, refreshState(preferencesOf(refreshedAt to now - REFRESH_DONE_MILLIS * 4), now))
        assertEquals(RefreshState.IDLE, refreshState(preferencesOf(refreshedAt to now + 60_000), now))
    }
}
