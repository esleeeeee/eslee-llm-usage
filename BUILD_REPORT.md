# Build report

Status: v0.1.5 published. Unit/lint/signed APK builds and both Android WebView integration tests passed in [CI 34564611921](https://github.com/esleeeeee/eslee-llm-usage/actions/runs/34564611921).

## v0.1.5 (2026-09-11)

- Replace paste/manual-page workaround with account-profile WebView collection for scheduled, app, and widget refresh.
- Consumer scheduling: 15-minute WorkManager requests (OS may delay), 5-minute refresh while the app screen is active. Wi-Fi-only and automatic-sync-off settings are honored.
- Read current provider content before navigating; retain accounts/sessions on closing the login screen; return the durable save result directly.
- Review corrections: authenticate before parsing, use the worker run start for retry accounting, stop DOM polling with the Activity lifecycle.
- Local `testDebugUnitTest`: 29 tests, 0 failures/errors. `lintDebug`: 0 errors, 55 warnings.
- Initial complete debug/release/androidTest build passed. Final production-source fixes passed unit/lint/androidTest compilation. CI rebuilds all signed artifacts from the committed source.
- New Android integration tests cover read-button persistence without a navigation loop, delayed DOM, cookies retained across collector WebViews, repeated DB updates, actual Glance RemoteViews values, and last-success retention.
- Android API 35 integration: 2 tests passed, 0 failed. Tests verify the read button stores a new snapshot without changing the redirected route, and repeated collection retains profile cookies and renders 40% in widget RemoteViews.
- Two test-fixture issues were corrected before this result: JUnit methods need a Unit return type; loadDataWithBaseURL needs an explicit history URL for the navigation assertion. No assertions were removed.
- The integration tests use local fixture HTML and a test cookie, not real Google/Grok credentials. Actual provider authentication and the user's phone/launcher remain unverified.
- [Release v0.1.5](https://github.com/esleeeeee/eslee-llm-usage/releases/tag/v0.1.5): signed debug and release APKs; tag points to `4ec15e8ad0993834d342d656b75af419ded35dea`.
- See [V015_AUTO_SYNC.md](docs/V015_AUTO_SYNC.md) for scope and agent responsibilities.

Local evidence: `.handoff/v015-build.log`, `.handoff/v015-final-local.log` (Git-ignored).

## v0.1.4 (2026-09-10)

- Fix account-profile OAuth popup lifetime, post-login Usage navigation and delayed page reads.
- Parser 1.2.0: Korean Codex limits, multiline remaining values, local reset times, zero credits, ARIA text.
- Add explicit browser text import, recorded as USER_ENTERED; it does not transfer authentication or auto-refresh.
- versionCode 5. GitHub Actions uses the existing upload key and publishes both debug and release APKs.
- Full change/device checklist/agent ownership: [V014_FIX_REPORT.md](docs/V014_FIX_REPORT.md).

| Check | Result |
| --- | --- |
| testDebugUnitTest | PASS — 28 tests, 0 failures/errors |
| lintDebug | PASS — 0 errors, 57 warnings |
| assembleDebug | PASS — local default debug signing, not distribution artifact |
| assembleRelease | PASS — local unsigned artifact |
| assembleDebugAndroidTest | PASS — compilation only |
| Google number matching / Grok device verification / phone UI and widget | NOT RUN — no connected Android device |

Initial offline build failed because dependencies were missing from that cache. The configured existing gradle-home cache resolved dependencies. An in-progress test import error was fixed. Lint initially had an internal Kotlin analysis error; the final stable source passed a focused lint rerun without disabling rules. Final APK/test build passed separately. Local logs: .handoff/v014-lint.log and .handoff/v014-apks.log (ignored by Git).

Install the GitHub Actions-signed APK, not the locally default-signed/unsigned output, to preserve v0.1.3 update compatibility. Publication status and exact CI link are recorded in Notion and the GitHub Release.

## v0.1.3 (2026-09-09)

Android-only. Desktop Collector was not adopted.

- ChatGPT Consumer `usageUrl` is `https://chatgpt.com/codex/settings/usage` (Codex Usage, not chatgpt.com home).
- Grok `usageUrl` is `https://grok.com/?_s=usage`.
- Parser 1.1.0 reads Codex 5h/weekly/reserve/banked resets/credits and Grok weekly/product/reset/credits.
- After sign-in confirmation the WebView opens the usage page and retries DOM text reads for SPA content.
- Isolated WebView profiles are unchanged. Custom Tabs are not used for collection (no session return). GeckoView is not shipped.
- Google WebView block and Grok device-verification pages show a specific message. Email sign-in remains the in-app path.
- `versionCode` is 4.
- Device usage-number reads are **not complete**.

| Command | Result |
| --- | --- |
| `testDebugUnitTest` | PASS — 24 tests, 0 failures |
| `lintDebug` | PASS — 0 errors, 46 warnings |
| `assembleDebug` | PASS — signed with upload key |
| `assembleRelease` | PASS — signed with upload key |
| `assembleDebugAndroidTest` | PASS — compile only |
| Device Codex/Grok usage numbers | NOT RUN |

| File | SHA-256 |
| --- | --- |
| `eslee-llm-usage-v0.1.3-debug.apk` | `ee24ee581bf269c376dfef0df32c994ee6c535865a9c4e52849d0934200b5b9b` |
| `eslee-llm-usage-v0.1.3-release.apk` | `0d9d08d0dc6403622cdfdc57509e21f5c0cae0e55c77fcd74d4d6e93679606fa` |

## v0.1.2 (2026-09-09)

## v0.1.2 (2026-09-09)

Device testing of v0.1.1 found update install failure, social-login "not allowed" toasts, and account delete no-ops.

- GitHub Actions now signs debug and release with a fixed upload keystore stored only as Actions secrets. v0.1.0/v0.1.1 used ephemeral debug keys, so they cannot be recovered; install v0.1.2 after uninstalling those builds. Later versions share this key.
- `versionCode` is 3.
- Consumer WebView allowlists include Google/Apple/Microsoft/X OAuth hosts. `intent:`/`market:` still cannot enter the isolated profile; the UI tells the user to use email sign-in. Host+path are logged without query strings.
- Account delete always removes the Room row, snapshots, credentials, and widget links even if WebView profile cleanup fails.

| Command | Result |
| --- | --- |
| `testDebugUnitTest` | PASS — 23 tests, 0 failures |
| `lintDebug` | PASS |
| `assembleDebug` | PASS — signed with upload key |
| `assembleRelease` | PASS — signed with upload key |
| `assembleDebugAndroidTest` | PASS — compile only |
| Device retest | NOT RUN |

| File | SHA-256 |
| --- | --- |
| `eslee-llm-usage-v0.1.2-debug.apk` | `cc96e71224d39e1789823260a3dcc539da7726a451baafa5a3ea3829bdbc02c6` |
| `eslee-llm-usage-v0.1.2-release.apk` | `faca23439a6bc84b72c363bbee8e646476b0d0b6ce0af62cf41b3fc648563863` |

## v0.1.1 (2026-09-09)

Device testing of v0.1.0 found status/navigation overlap, a dead ChatGPT WebView session, and consumer add opening the generic home page. This patch:

- Applies edge-to-edge plus `statusBars`, `navigationBars`, `displayCutout`, and `ime` padding on Compose and the WebView session
- Puts native WebView controls in an elevated toolbar above a clipped WebView host so the page cannot steal toolbar hits
- Uses provider `allowedHosts` for login-check/read (not only the start host), so `auth.openai.com` is not a silent no-op
- Starts ChatGPT at `https://chatgpt.com/auth/login` and Grok at `https://grok.com/sign-in`
- Confirms sign-in from URL, password fields, login/logout copy, and composer chrome, then offers the usage page

| Command | Result |
| --- | --- |
| `testDebugUnitTest` | PASS — 22 tests, 0 failures (includes `PageAuthStateTest`) |
| `lintDebug` | PASS — 0 errors, 46 warnings |
| `assembleDebug` | PASS |
| `assembleRelease` | PASS — unsigned |
| `assembleDebugAndroidTest` | PASS — compile only |
| Device retest | NOT RUN |
| Live Provider login | NOT RUN |

v0.1.0 artifacts were kept. New local files:

| File | SHA-256 |
| --- | --- |
| `eslee-llm-usage-v0.1.1-debug.apk` | `72b33b207df76666c8f44f1abfe32427277cff0388518ef9e902835c1dddee2a` |
| `eslee-llm-usage-v0.1.1-release-unsigned.apk` | `7b037c3c7f35415b76cc0d5900b86bd8d6a38955a0a5f6f4dbf925e193df1a0d` |

## Environment

- JDK: Eclipse Temurin 21.0.12.1 (`D:\eslee\toolchains\jdk\jdk-21.0.12.1+1`)
- Gradle: 9.3.1 wrapper
- AGP: 9.1.1
- Android compile/target SDK: 37 (installed package `platforms;android-37.0`)
- Build Tools: 36.0.0
- Gradle user home: `D:\eslee\toolchains\gradle-home`
- Temp: `D:\eslee\toolchains\tmp`

## Commands

| Command | Result |
| --- | --- |
| `testDebugUnitTest` | PASS — 19 tests, 0 failures (`WebNavigationPolicyTest` 1, `ConsumerUsageParserTest` 5, `OfficialUsageProvidersTest` 7, `UsageModelsTest` 5, `WidgetLayoutResolverTest` 1) |
| `assembleDebug` | PASS — `app/build/outputs/apk/debug/app-debug.apk` |
| `lintDebug` | PASS — 0 errors, 49 warnings |
| `assembleDebugAndroidTest` | PASS — instrumentation APK compiled; tests not executed (no adb device) |
| `assembleRelease` | PASS — unsigned (`app/build/outputs/apk/release/app-release-unsigned.apk`) |
| `connectedDebugAndroidTest` | SKIPPED — `adb devices` empty; emulator previously failed (no hypervisor, low commit memory) |
| Live Provider login/API | SKIPPED — no credentials supplied |

Unused Robolectric was removed from JVM tests. Room/instrumentation tests stay on `androidTest`.

## Artifacts (local, not committed)

Copied under `artifacts/` (gitignored):

| File | SHA-256 |
| --- | --- |
| `eslee-llm-usage-v0.1.0-debug.apk` | `774182789855cb29d8953317a3a7751bcb5ecae9907cff7fc5a15e33612b176b` |
| `eslee-llm-usage-v0.1.0-release-unsigned.apk` | `ad61ae1eaecf6eba9ead2abea9fbb78d836c280c510e0ea7334a60358b7dd1b2` |

Release is unsigned. Install requires a local signing key (`RELEASE_STORE_FILE` and related env vars). Debug APK can be installed on API 28+.

## Not verified on a device

- First launch empty state, Demo account, multi-account dashboard
- WebView MULTI_PROFILE isolation on a real WebView
- Widget configure / resize / tap / overflow on a launcher
- Live ChatGPT / Claude / Grok / official API credentials
