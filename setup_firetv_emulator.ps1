# Fire TV Stick HD (Fire OS 7 / Android 9 相当) エミュレーター自動セットアップスクリプト

$ErrorActionPreference = "Stop"

$sdkPath = "$env:LOCALAPPDATA\Android\Sdk"
$avdManager = "$sdkPath\cmdline-tools\latest\bin\avdmanager.bat"
$sdkManager = "$sdkPath\cmdline-tools\latest\bin\sdkmanager.bat"
$androidCli = "C:\Users\Gisa_M3\AppData\AndroidCLI\android.exe"
$avdName = "FireTV_Stick_HD"
$systemImage = "system-images;android-28;android-tv;x86"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  Fire TV Stick HD 最適化 AVD セットアップ開始" -ForegroundColor Cyan
Write-Host "  - ターゲット: Fire OS 7 (Android 9 / API 28)" -ForegroundColor Cyan
Write-Host "  - 解像度: 1080p (1920x1080, TV-DPI)" -ForegroundColor Cyan
Write-Host "  - RAM制限: 1536MB (1.5GB) / VM Heap: 192MB" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. システムイメージの確認・ダウンロード
Write-Host "`n[1/3] Android TV (API 28) システムイメージをダウンロード中..." -ForegroundColor Yellow
if (Test-Path $androidCli) {
    & $androidCli sdk install "system-images/android-28/android-tv/x86" "platforms/android-28"
} else {
    & $sdkManager $systemImage "platforms/android-28"
}

# 2. AVD の作成
Write-Host "`n[2/3] Fire TV 仕様の AVD '$avdName' を作成中..." -ForegroundColor Yellow
$echoNo = "no"
$echoNo | & $avdManager create avd --name $avdName --package $systemImage --device "tv_1080p" --force

# 3. 低RAM・キーボード最適化 config.ini の更新
Write-Host "`n[3/3] 実機低RAM（1.5GB）およびリモコンキーボード設定を適用中..." -ForegroundColor Yellow
$configPath = "$env:USERPROFILE\.android\avd\$avdName.avd\config.ini"

if (Test-Path $configPath) {
    $configContent = Get-Content $configPath
    $newConfig = @()
    $appliedKeys = @{}

    foreach ($line in $configContent) {
        if ($line -match "^hw\.ramSize=") {
            $newConfig += "hw.ramSize=1536"
            $appliedKeys["hw.ramSize"] = $true
        } elseif ($line -match "^vm\.heapSize=") {
            $newConfig += "vm.heapSize=192"
            $appliedKeys["vm.heapSize"] = $true
        } elseif ($line -match "^hw\.keyboard=") {
            $newConfig += "hw.keyboard=yes"
            $appliedKeys["hw.keyboard"] = $true
        } else {
            $newConfig += $line
        }
    }

    if (-not $appliedKeys["hw.ramSize"]) { $newConfig += "hw.ramSize=1536" }
    if (-not $appliedKeys["vm.heapSize"]) { $newConfig += "vm.heapSize=192" }
    if (-not $appliedKeys["hw.keyboard"]) { $newConfig += "hw.keyboard=yes" }

    $newConfig | Set-Content -Path $configPath -Encoding UTF8
    Write-Host "config.ini のチューニングが完了しました: $configPath" -ForegroundColor Green
} else {
    Write-Warning "config.ini が見つかりませんでした: $configPath"
}

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "  セットアップ完了！" -ForegroundColor Green
Write-Host "  以下のコマンドでエミュレーターを起動できます:" -ForegroundColor Green
Write-Host "  & `"$sdkPath\emulator\emulator.exe`" -avd $avdName" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Green
