# Build report

Status: v0.1.3 local unit tests, lint, signed debug APK, and signed release APK completed. Device read of Codex/Grok usage numbers has not been run.

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
