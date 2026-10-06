package com.eslee.llmusage.core.web

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eslee.llmusage.core.database.UsageDatabase
import com.eslee.llmusage.core.model.UsageBucket
import com.eslee.llmusage.core.model.UsageSnapshot
import com.eslee.llmusage.core.security.CredentialStore
import com.eslee.llmusage.provider.ProviderErrorCode
import com.eslee.llmusage.provider.ProviderRegistry
import com.eslee.llmusage.provider.ProviderResult
import com.eslee.llmusage.settings.AppSettings
import com.eslee.llmusage.settings.SettingsStore
import com.eslee.llmusage.usage.UsageRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The background reader against pages that behave like the live ones: figures that change, lists that arrive late. */
@RunWith(AndroidJUnit4::class)
class BackgroundPageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var previousSettings: AppSettings

    @Before fun pauseScheduledJobs() = runBlocking {
        val settings = SettingsStore(context)
        previousSettings = settings.current()
        settings.update(previousSettings.copy(intervalMinutes = 0))
        androidx.work.WorkManager.getInstance(context).cancelAllWork().result.get()
        Unit
    }

    @After fun restoreSettings() = runBlocking { SettingsStore(context).update(previousSettings) }

    /**
     * Reported on v0.2.8 with the phone's trace: Grok's page gives its figure an
     * accessible label and animates the digits one node each, and the reader took the
     * last digit alone (2% for 52%). Here the digits sit in a shadow root, beyond the
     * painted text, as the live page's do.
     */
    @Test fun aFigureDrawnOneDigitPerNodeIsReadWhole(): Unit = runBlocking {
        val reset = moment(3)
        val weekly = readGrok("""
            <h2>매주 SuperGrok 한도</h2>
            <div id="figure" aria-label="52%"></div><div>중고</div>
            <div>Imagine</div><div id="share" aria-label="52%"></div>
            <div><span>${korean(reset)}</span> <span>초기화</span></div>
            <script>
              ['figure', 'share'].forEach(function(id){
                document.getElementById(id).attachShadow({mode: 'open'}).innerHTML = '<div>5</div><div>2</div><div>%</div>';
              });
            </script>
        """)
        assertEquals(52.0, weekly.usedPercent!!, 0.0)
        assertEquals(48.0, weekly.remainingPercent!!, 0.0)
        assertEquals(reset.toInstant().toEpochMilli(), weekly.resetAt)
    }

    /** A page that paints a cached figure and replaces it once its own request returns: the read waits for the new one. */
    @Test fun aFigureThePageReplacesAfterLoadIsReadInsteadOfTheFirstOne(): Unit = runBlocking {
        val thisWeek = moment(3)
        val weekly = readGrok("""
            <h2>매주 SuperGrok 한도</h2>
            <div id="used">12%</div><div>중고</div>
            <div id="reset">${korean(moment(-3))} 초기화</div>
            <script>
              setTimeout(function(){ requestAnimationFrame(function(){
                document.getElementById('used').textContent = '52%';
                document.getElementById('reset').textContent = '${korean(thisWeek)} 초기화';
              }); }, 1500);
            </script>
        """)
        assertEquals(52.0, weekly.usedPercent!!, 0.0)
        assertEquals(thisWeek.toInstant().toEpochMilli(), weekly.resetAt)
    }

    /** Reported: an account with three banked resets showed none. A list that renders once scrolled into view is read. */
    @Test fun resetsThatRenderOnlyInViewAreRead(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, UsageDatabase::class.java).build()
        val reader = WebUsageReader(context) { web, _ ->
            web.loadDataWithBaseURL("https://chatgpt.com/codex/settings/usage", """
                <html><body>5시간 사용 한도<br>45%<br>남음<br>주간 사용 한도<br>66%<br>남음
                <div style="height:6000px"></div>
                <div id="resets" style="min-height:20px"></div>
                <script>
                  new IntersectionObserver(function(entries){
                    var list = document.getElementById('resets');
                    if (!entries[0].isIntersecting || list.innerHTML) return;
                    list.innerHTML = '사용량 한도 재설정<br>' + [4, 5, 6].map(function(day){
                      return '전체 재설정(주간 + 5시간)<br>10월 ' + day + '일 오전 9:50에 만료<br>재설정 사용';
                    }).join('<br>');
                  }).observe(document.getElementById('resets'));
                </script></body></html>
            """.trimIndent(), "text/html", "UTF-8", "https://chatgpt.com/codex/settings/usage")
        }
        val repository = UsageRepository(context, database, ProviderRegistry(false), CredentialStore(context), SettingsStore(context), reader::fetch)
        val id = repository.addAccount("chatgpt", "Resets below the fold")
        try {
            repository.refresh(id)
            val snapshot = requireNotNull(repository.latest(id))
            assertEquals(listOf("session", "weekly"), snapshot.buckets.map { it.id })
            assertEquals(45.0, snapshot.buckets.first { it.id == "session" }.remainingPercent!!, 0.0)
            assertTrue(snapshot.resetCreditsKnown)
            assertEquals(3, snapshot.resetCredits.size)
        } finally { repository.deleteAccount(id); database.close() }
    }

    /** Percentages are painted normally; the dates and banked resets are only in open shadow roots. */
    @Test fun paintedCodexFiguresKeepShadowRootResetDatesAndCredits(): Unit = runBlocking {
        val sessionReset = moment(1)
        val weeklyReset = moment(4)
        val expiry = moment(6)
        val snapshot = readCodex("""
            <div>5시간 사용 한도</div><div>45% 남음</div><div id="session-reset"></div>
            <div>주간 사용 한도</div><div>66% 남음</div><div id="weekly-reset"></div>
            <div id="credits"></div>
            <script>
              document.getElementById('session-reset').attachShadow({mode:'open'}).innerHTML = '<div>${korean(sessionReset)} 초기화</div>';
              document.getElementById('weekly-reset').attachShadow({mode:'open'}).innerHTML = '<div>${korean(weeklyReset)} 초기화</div>';
              document.getElementById('credits').attachShadow({mode:'open'}).innerHTML =
                '<div>사용량 한도 재설정</div><div>사용 가능 2</div><div>전체 재설정(주간 + 5시간)</div>' +
                '<div>${expiry.monthValue}월 ${expiry.dayOfMonth}일 오후 4:39에 만료</div><div>재설정 사용</div>';
            </script>
        """)
        val session = snapshot.buckets.single { it.id == "session" }
        val weekly = snapshot.buckets.single { it.id == "weekly" }
        assertEquals(45.0, session.remainingPercent!!, 0.0)
        assertEquals(66.0, weekly.remainingPercent!!, 0.0)
        assertEquals(sessionReset.toInstant().toEpochMilli(), session.resetAt)
        assertEquals(weeklyReset.toInstant().toEpochMilli(), weekly.resetAt)
        assertTrue("Structural reset credits must survive repository persistence", snapshot.resetCreditsKnown)
        assertEquals(2, snapshot.resetCredits.size)
        assertEquals(expiry.toInstant().toEpochMilli(), snapshot.resetCredits.first().expiresAt)
    }

    /** The October Korean dashboard exposes dates through accessibility, using a new reset heading. */
    @Test fun theCurrentKoreanDashboardCapturesAccessibleGmtDatesAndResetCredits(): Unit = runBlocking {
        val at = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(2)
            .atTime(10, 25, 19).atZone(java.time.ZoneOffset.UTC)
        val expiry = at.plusDays(14).withHour(19).withMinute(29).withSecond(0)
        val date = "${at.year}년 ${at.monthValue}월 ${at.dayOfMonth}일 화요일 오전 10시 25분 19초 GMT"
        val expires = "${expiry.monthValue}. ${expiry.dayOfMonth}. 오후 7:29 GMT 만료"
        val snapshot = readCodex("""
            <div>5시간 단위 한도</div><div aria-label="$date"></div><div>75% 남음</div>
            <div>주간 사용 한도</div><div aria-label="$date"></div><div>96% 남음</div>
            <div>사용 한도 초기화</div>
            <div>초기화를 사용해 5시간 한도나 주간 한도, 또는 두 한도를 모두 복원하세요</div>
            <div>사용 한도 재설정</div><div>사용 가능 2</div>
            <div>전체 재설정(주간 + 5시간)</div><div>$expires</div><button>초기화 사용</button>
            <div>전체 재설정(주간 + 5시간)</div><div>$expires</div><button>초기화 사용</button>
            <div>크레딧 사용 내역</div>
        """)
        assertEquals(listOf("session", "weekly"), snapshot.buckets.map { it.id })
        assertEquals(at.toInstant().toEpochMilli(), snapshot.buckets.single { it.id == "session" }.resetAt)
        assertEquals(at.toInstant().toEpochMilli(), snapshot.buckets.single { it.id == "weekly" }.resetAt)
        assertTrue(snapshot.resetCreditsKnown)
        assertEquals(2, snapshot.resetCredits.size)
        assertTrue(snapshot.resetCredits.all { it.expiresAt == expiry.toInstant().toEpochMilli() })
    }

    /** An explicit zero is complete data, so it must not consume the seven-second missing-list wait. */
    @Test fun explicitZeroCreditsFinishesWithoutWaitingForAnAbsentList(): Unit = runBlocking {
        var loadedAt = 0L
        var savedAt = 0L
        val snapshot = readCodex("""
            <div>5시간 사용 한도</div><div>45% 남음</div><div>${korean(moment(1))} 초기화</div>
            <div>주간 사용 한도</div><div>66% 남음</div><div>${korean(moment(4))} 초기화</div>
            <div>사용량 한도 재설정</div><div>사용 가능 0</div>
        """, onLoad = { loadedAt = SystemClock.elapsedRealtime() }, onSaved = { savedAt = SystemClock.elapsedRealtime() })
        // Exclude profile teardown and account cleanup from the reader's settling budget.
        val elapsed = savedAt - loadedAt
        assertTrue(snapshot.resetCreditsKnown)
        assertTrue(snapshot.resetCredits.isEmpty())
        assertTrue("Explicit zero took ${elapsed}ms; it should settle without RESETS_WAIT", elapsed < 6_500)
    }

    /** The official page can scroll its usage panel while the document itself remains fixed. */
    @Test fun resetsInsideAnOverflowPanelAreScrolledIntoView(): Unit = runBlocking {
        val snapshot = readCodex("""
            <style>html,body { margin:0; height:100%; overflow:hidden; }</style>
            <div id="panel" style="height:400px; overflow:auto">
              <div>5시간 사용 한도</div><div>45% 남음</div><div>${korean(moment(1))} 초기화</div>
              <div>주간 사용 한도</div><div>66% 남음</div><div>${korean(moment(4))} 초기화</div>
              <div style="height:6000px"></div><div id="resets" style="min-height:20px"></div>
            </div>
            <script>
              new IntersectionObserver(function(entries){
                var list = document.getElementById('resets');
                if (!entries[0].isIntersecting || list.innerHTML) return;
                list.innerHTML = '사용량 한도 재설정<br>사용 가능 3<br>' + [1, 2, 3].map(function(index){
                  return '전체 재설정(주간 + 5시간)<br>재설정 사용';
                }).join('<br>');
              }, {root: document.getElementById('panel')}).observe(document.getElementById('resets'));
            </script>
        """)
        assertEquals(45.0, snapshot.buckets.single { it.id == "session" }.remainingPercent!!, 0.0)
        assertTrue("The nested panel's lazy reset list was never reached", snapshot.resetCreditsKnown)
        assertEquals(3, snapshot.resetCredits.size)
    }

    private suspend fun readCodex(body: String, onLoad: () -> Unit = {}, onSaved: () -> Unit = {}): UsageSnapshot {
        val database = Room.inMemoryDatabaseBuilder(context, UsageDatabase::class.java).build()
        val reader = WebUsageReader(context) { web, _ ->
            onLoad()
            web.loadDataWithBaseURL("https://chatgpt.com/codex/settings/usage", "<html><body>${body.trimIndent()}</body></html>",
                "text/html", "UTF-8", "https://chatgpt.com/codex/settings/usage")
        }
        val repository = UsageRepository(context, database, ProviderRegistry(false), CredentialStore(context), SettingsStore(context), reader::fetch)
        val id = repository.addAccount("chatgpt", "Codex metadata regression")
        try {
            repository.refresh(id)
            val snapshot = requireNotNull(repository.latest(id)) { "no reading saved; account ended as ${repository.account(id)?.lastErrorCode}" }
            onSaved()
            return snapshot
        } finally { repository.deleteAccount(id); database.close() }
    }

    /** Phone trace: an explicit dashboard error used to wait the whole 45-second deadline. */
    @Test fun anExplicitDashboardErrorEndsPromptlyAndKeepsTheLastReading(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, UsageDatabase::class.java).build()
        var loadedAt = 0L
        val reader = WebUsageReader(context) { web, _ ->
            loadedAt = android.os.SystemClock.elapsedRealtime()
            web.loadDataWithBaseURL("https://chatgpt.com/codex/settings/usage", """
                <html><body>사용량<br>개요<br>사용량 분석<br>
                사용량 설정을 불러올 수 없습니다.<br>다시 시도</body></html>
            """.trimIndent(), "text/html", "UTF-8", "https://chatgpt.com/codex/settings/usage")
        }
        val repository = UsageRepository(context, database, ProviderRegistry(false), CredentialStore(context), SettingsStore(context), reader::fetch)
        val id = repository.addAccount("chatgpt", "Dashboard error")
        try {
            repository.recordWeb(id, "5-hour usage\n75% remaining")
            val previous = requireNotNull(repository.latest(id)).snapshotId
            repository.refresh(id)
            assertEquals("NETWORK", repository.account(id)?.lastErrorCode)
            assertEquals(previous, repository.latest(id)?.snapshotId)
            assertTrue("Explicit dashboard error waited for the full deadline",
                android.os.SystemClock.elapsedRealtime() - loadedAt < 10_000)
        } finally { repository.deleteAccount(id); database.close() }
    }

    /** A page that only ever shows the period before its reset is not saved as a reading, and the last one stays. */
    @Test fun aPageStuckBeforeItsResetIsNotASuccess(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, UsageDatabase::class.java).build()
        val repository = UsageRepository(context, database, ProviderRegistry(false), CredentialStore(context), SettingsStore(context))
        val id = repository.addAccount("grok", "Lapsed page")
        try {
            val current = repository.recordWeb(id, "매주 SuperGrok 한도\n30%\n중고\n${korean(moment(3))} 초기화")
            assertTrue("current page: $current", current is ProviderResult.Success)
            val kept = repository.latest(id)?.snapshotId
            val stale = repository.recordWeb(id, "매주 SuperGrok 한도\n12%\n중고\n${korean(moment(-3))} 초기화")
            assertEquals(ProviderErrorCode.STALE_PAGE, (stale as ProviderResult.Failure).code)
            assertEquals(kept, repository.latest(id)?.snapshotId)
            assertEquals("STALE_PAGE", repository.account(id)?.lastErrorCode)
        } finally { repository.deleteAccount(id); database.close() }
    }

    /** Reads a Grok usage page with [body] through the background reader and returns the weekly limit it saved. */
    private suspend fun readGrok(body: String): UsageBucket {
        val database = Room.inMemoryDatabaseBuilder(context, UsageDatabase::class.java).build()
        val reader = WebUsageReader(context) { web, _ ->
            web.loadDataWithBaseURL("https://grok.com/?_s=usage", "<html><body>${body.trimIndent()}</body></html>", "text/html", "UTF-8", "https://grok.com/?_s=usage")
        }
        val repository = UsageRepository(context, database, ProviderRegistry(false), CredentialStore(context), SettingsStore(context), reader::fetch)
        val id = repository.addAccount("grok", "Grok page")
        try {
            repository.refresh(id)
            val snapshot = requireNotNull(repository.latest(id)) { "no reading saved; the account ended as ${repository.account(id)?.lastErrorCode}" }
            return snapshot.buckets.single { it.id == "weekly" }
        } finally { repository.deleteAccount(id); database.close() }
    }

    /** 4:39 PM on the day [days] from today, local time: reset times stay on the right side of now as the calendar moves. */
    private fun moment(days: Long): java.time.ZonedDateTime =
        java.time.LocalDate.now().plusDays(days).atTime(16, 39).atZone(java.time.ZoneId.systemDefault())

    /** The way Grok's Korean page writes a moment: "2026년 10월 2일 오후 4:39". */
    private fun korean(at: java.time.ZonedDateTime): String =
        "${at.year}년 ${at.monthValue}월 ${at.dayOfMonth}일 ${if (at.hour < 12) "오전" else "오후"} ${(at.hour + 11) % 12 + 1}:${"%02d".format(at.minute)}"
}
