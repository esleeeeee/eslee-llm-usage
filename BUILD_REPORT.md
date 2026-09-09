# Build report

Status: local debug APK, unsigned release APK, unit tests, and lint completed. Instrumentation and live Provider checks were not run.

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
