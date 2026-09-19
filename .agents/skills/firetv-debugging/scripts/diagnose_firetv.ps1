# Fire TV Stick Diagnostic Analyzer
param(
    [string]$PackageName = "com.firetube.tv",
    [switch]$SaveScreenshot = $false,
    [string]$OutputDir = "debug_output"
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $adb = "adb"
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Fire TV Stick Diagnostic Analyzer" -ForegroundColor Cyan
Write-Host "  Target Package: $PackageName" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Step 1: Device check
$devices = & $adb devices | Select-String -Pattern "device$"
if (-not $devices) {
    Write-Host "[ERROR] No connected Fire TV device or emulator found." -ForegroundColor Red
    exit 1
}
Write-Host "[1/6] Connected device: $($devices.Line)" -ForegroundColor Green

$model = (& $adb shell getprop ro.product.model).Trim()
$fireOs = (& $adb shell getprop ro.build.version.fireos).Trim()
$androidVer = (& $adb shell getprop ro.build.version.release).Trim()
$apiLevel = (& $adb shell getprop ro.build.version.sdk).Trim()
$arch = (& $adb shell getprop ro.product.cpu.abi).Trim()

Write-Host "      Model      : $model" -ForegroundColor Yellow
Write-Host "      OS/API     : Fire OS $fireOs (Android $androidVer / API $apiLevel) [$arch]" -ForegroundColor Yellow

# Step 2: Current Focus & Activity
Write-Host "`n[2/6] Inspecting Focus & Resumed Activity..." -ForegroundColor Green
$currentFocus = & $adb shell "dumpsys window windows | grep -E 'mCurrentFocus|mFocusedApp'"
Write-Host "      Focus Window    : $currentFocus" -ForegroundColor Gray

$topActivity = & $adb shell "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'"
Write-Host "      Resumed Activity: $topActivity" -ForegroundColor Gray

# Step 3: Memory Consumption
Write-Host "`n[3/6] Inspecting Memory Usage (dumpsys meminfo)..." -ForegroundColor Green
$meminfo = & $adb shell "dumpsys meminfo $PackageName | grep -E 'TOTAL PSS:|Native Heap:|Dalvik Heap:|Views:|Activities:'"
if ($meminfo) {
    $meminfo | ForEach-Object { Write-Host "      $_" -ForegroundColor Gray }
} else {
    Write-Host "      [INFO] Process $PackageName is not currently running." -ForegroundColor DarkYellow
}

# Step 4: Rendering Performance (Jank)
Write-Host "`n[4/6] Inspecting Rendering Performance (dumpsys gfxinfo)..." -ForegroundColor Green
$gfxinfo = & $adb shell "dumpsys gfxinfo $PackageName | grep -E 'Total frames rendered|Janky frames|Number Missed Vsync'"
if ($gfxinfo) {
    $gfxinfo | ForEach-Object { Write-Host "      $_" -ForegroundColor Gray }
}

# Step 5: Crash and Exceptions Scan
Write-Host "`n[5/6] Scanning Recent Crash / ANR / Exceptions..." -ForegroundColor Green
$appErrors = & $adb logcat -d -t 150 | Select-String -Pattern "FATAL EXCEPTION|OutOfMemoryError|MediaCodec.*error|PlaybackActivity.*Error"
if ($appErrors) {
    Write-Host "      [ALERT] Detected errors in logcat:" -ForegroundColor Red
    $appErrors | Select-Object -Last 10 | ForEach-Object { Write-Host "      $_" -ForegroundColor Red }
} else {
    Write-Host "      [OK] No fatal crash or error found in recent 150 logcat lines." -ForegroundColor DarkGreen
}

# Step 6: Screenshot capture
if ($SaveScreenshot) {
    Write-Host "`n[6/6] Saving Screenshot..." -ForegroundColor Green
    if (-not (Test-Path $OutputDir)) {
        New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
    }
    $timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
    $filename = "$OutputDir/firetv_screen_$timestamp.png"
    & $adb shell screencap -p /sdcard/firetv_screen_tmp.png
    & $adb pull /sdcard/firetv_screen_tmp.png $filename | Out-Null
    & $adb shell rm /sdcard/firetv_screen_tmp.png
    Write-Host "      Saved to: $filename" -ForegroundColor Cyan
}

Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host "  Diagnosis Complete" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
