package com.eslee.llmusage.sync

import com.eslee.llmusage.settings.AppSettings
import org.junit.Assert.*
import org.junit.Test

class AutomaticSyncPolicyTest {
    @Test fun unmeteredMobileAndWifiAreBothAllowed() {
        assertTrue(allowsAutomaticSync(AppSettings(wifiOnly = true), isMetered = false))
    }

    @Test fun meteredWifiAndMobileAreBothBlockedWhenCostRestrictionIsEnabled() {
        assertFalse(allowsAutomaticSync(AppSettings(wifiOnly = true), isMetered = true))
    }

    @Test fun unrestrictedAutomaticSyncStillAllowsMeteredNetworks() {
        assertTrue(allowsAutomaticSync(AppSettings(wifiOnly = false), isMetered = true))
    }

    @Test fun disabledAutomaticSyncStaysDisabledOnEitherNetwork() {
        for (metered in listOf(false, true)) {
            assertFalse(allowsAutomaticSync(AppSettings(intervalMinutes = 0), isMetered = metered))
        }
    }
}
