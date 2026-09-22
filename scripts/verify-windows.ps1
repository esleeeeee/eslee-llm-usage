param(
    [string]$ToolchainRoot = (Join-Path $env:USERPROFILE '.local\android-dev'),
    [switch]$Connected
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$jdk = Get-ChildItem (Join-Path $ToolchainRoot 'jdk') -Directory -Filter 'jdk-*' | Select-Object -First 1
if (!$jdk) { throw 'Install JDK 21 under <ToolchainRoot>/jdk first. See docs/HANDOFF_HOME.md.' }
$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = Join-Path $ToolchainRoot 'sdk'
$env:PATH = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:PATH"
if (!(Test-Path "$env:ANDROID_HOME\platform-tools\adb.exe")) { throw 'Android SDK platform-tools are missing.' }

# Windows JVM tests cannot reliably load classes from a Korean/OneDrive path.
# A junction exposes the same files through ASCII; nothing is moved or copied.
$buildRoot = $projectRoot
if ($projectRoot -match '[^\x00-\x7F]') {
    $buildRoot = Join-Path $ToolchainRoot 'project'
    if (Test-Path $buildRoot) {
        $link = Get-Item -LiteralPath $buildRoot
        if ($link.LinkType -ne 'Junction' -or $link.Target -ne $projectRoot) {
            throw "Existing $buildRoot is not a junction to this repository. Use another ToolchainRoot."
        }
    } else {
        New-Item -ItemType Junction -Path $buildRoot -Target $projectRoot | Out-Null
    }
}
$reports = Join-Path $buildRoot 'artifacts\verification'
New-Item -ItemType Directory -Force -Path $reports | Out-Null
Push-Location $buildRoot
try {
    & .\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest 2>&1 |
        Tee-Object (Join-Path $reports 'build.log')
    if ($LASTEXITCODE -ne 0) { throw 'Gradle verification failed.' }
    if ($Connected) {
        $deviceLine = & adb devices | Select-String '^emulator-\d+\s+device$' | Select-Object -First 1
        if (!$deviceLine) { throw 'Start an Android emulator before using -Connected.' }
        $device = $deviceLine.ToString().Split("`t")[0]
        & adb -s $device install -r app/build/outputs/apk/debug/app-debug.apk
        if ($LASTEXITCODE -ne 0) { throw 'Debug APK install failed.' }
        & adb -s $device install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
        if ($LASTEXITCODE -ne 0) { throw 'Test APK install failed.' }
        $testLog = Join-Path $reports 'instrumentation.log'
        & adb -s $device shell am instrument -w -r com.eslee.llmusage.test/androidx.test.runner.AndroidJUnitRunner |
            Tee-Object $testLog
        if (!(Select-String -Path $testLog -Pattern '^OK \(\d+ tests\)$' -Quiet)) { throw 'Emulator tests failed. Inspect instrumentation.log.' }
        & adb -s $device pull /sdcard/Android/data/com.eslee.llmusage/files/qa (Join-Path $reports 'screenshots')
        if ($LASTEXITCODE -ne 0) { throw 'Screenshot export failed.' }
    }
} finally { Pop-Location }
