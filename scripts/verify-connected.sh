#!/usr/bin/env bash
set -u

# Preserve screenshots even when a test fails, and keep that test's exit status.
test_status=0
bash ./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true || test_status=$?
mkdir -p app/build/reports/emulator-screenshots
adb pull /sdcard/Android/data/com.eslee.llmusage/files/qa/. app/build/reports/emulator-screenshots/ || true
exit "$test_status"
