# Fire TV Remote Control Key Sender
param(
    [string]$Key = "CENTER",
    [string]$Sequence = "",
    [int]$DelayMs = 500
)

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) {
    $adb = "adb"
}

$keyMap = @{
    "UP"           = 19   # KEYCODE_DPAD_UP
    "DOWN"         = 20   # KEYCODE_DPAD_DOWN
    "LEFT"         = 21   # KEYCODE_DPAD_LEFT
    "RIGHT"        = 22   # KEYCODE_DPAD_RIGHT
    "CENTER"       = 23   # KEYCODE_DPAD_CENTER / OK
    "OK"           = 23
    "BACK"         = 4    # KEYCODE_BACK
    "HOME"         = 3    # KEYCODE_HOME
    "MENU"         = 82   # KEYCODE_MENU
    "PLAY_PAUSE"   = 85   # KEYCODE_MEDIA_PLAY_PAUSE
    "PLAY"         = 126  # KEYCODE_MEDIA_PLAY
    "PAUSE"        = 127  # KEYCODE_MEDIA_PAUSE
    "FF"           = 90   # KEYCODE_MEDIA_FAST_FORWARD
    "FAST_FORWARD" = 90
    "RW"           = 89   # KEYCODE_MEDIA_REWIND
    "REWIND"       = 89
    "VOL_UP"       = 24
    "VOL_DOWN"     = 25
    "SEARCH"       = 84   # KEYCODE_SEARCH
}

function Send-SingleKey([string]$k) {
    $code = $keyMap[$k.ToUpper().Trim()]
    if ($null -ne $code) {
        Write-Host "Sending Key: $k (code=$code)" -ForegroundColor Green
        & $adb shell input keyevent $code
    } else {
        Write-Host "[WARN] Unknown key: $k" -ForegroundColor Yellow
    }
}

if ($Sequence -ne "") {
    $keys = $Sequence -split ","
    foreach ($k in $keys) {
        Send-SingleKey $k
        Start-Sleep -Milliseconds $DelayMs
    }
} else {
    Send-SingleKey $Key
}
