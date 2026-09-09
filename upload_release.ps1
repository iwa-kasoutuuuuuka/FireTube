param(
    [string]$Tag = "v1.0.0",
    [string]$ReleaseName = "FireTube v1.0.0",
    [string]$Token = $env:GITHUB_TOKEN
)

$ErrorActionPreference = "Stop"
$apkPath = "e:\FireTube\app\build\outputs\apk\debug\app-debug.apk"
$repo = "iwa-kasoutuuuuuka/FireTube"

if (-not (Test-Path $apkPath)) {
    Write-Error "APK file not found: $apkPath. Please run './gradlew assembleDebug' first."
    exit 1
}

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  FireTube GitHub Release APK Uploader" -ForegroundColor Cyan
Write-Host "  - Target Repo: $repo" -ForegroundColor Cyan
Write-Host "  - Release Tag: $Tag" -ForegroundColor Cyan
Write-Host "  - APK: $apkPath" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 1. gh CLI がある場合
$ghExe = "e:\FireTube\.tools\bin\gh.exe"
if (Test-Path $ghExe) {
    try {
        Write-Host "`nAttempting upload via GitHub CLI..." -ForegroundColor Yellow
        & $ghExe release create $Tag $apkPath --title $ReleaseName --notes "Fire TV Stick HD 専用ネイティブ YouTube クライアント APK"
        Write-Host "Release created and APK uploaded successfully via GitHub CLI!" -ForegroundColor Green
        exit 0
    } catch {
        Write-Warning "GitHub CLI upload failed or not authenticated: $_"
    }
}

# 2. Token がある場合は REST API で直接アップロード
if ($Token) {
    Write-Host "`nUploading via GitHub REST API using GITHUB_TOKEN..." -ForegroundColor Yellow
    $headers = @{
        "Authorization" = "token $Token"
        "Accept"        = "application/vnd.github.v3+json"
    }

    # リリース作成
    $releaseBody = @{
        tag_name   = $Tag
        name       = $ReleaseName
        body       = "Fire TV Stick HD (Fire OS 7〜8 / 1.5GB RAM) 専用 YouTube ネイティブクライアント"
        draft      = $false
        prerelease = $false
    } | ConvertTo-Json

    $releaseRes = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases" -Method Post -Headers $headers -Body $releaseBody -ContentType "application/json"
    $uploadUrl = $releaseRes.upload_url -replace '\{\?name,label\}', "?name=app-debug.apk"

    # APK ファイルアップロード
    $bytes = [System.IO.File]::ReadAllBytes($apkPath)
    $uploadHeaders = @{
        "Authorization" = "token $Token"
        "Content-Type"  = "application/vnd.android.package-archive"
    }

    Invoke-RestMethod -Uri $uploadUrl -Method Post -Headers $uploadHeaders -Body $bytes
    Write-Host "APK uploaded successfully via REST API: $($releaseRes.html_url)" -ForegroundColor Green
    exit 0
}

Write-Host "`n==========================================================" -ForegroundColor Yellow
Write-Host "  自動アップロードを行うには以下のいずれかを行ってください:" -ForegroundColor Yellow
Write-Host "  1. Git タグを Push する (GitHub Actions が自動リリースを作成):" -ForegroundColor Cyan
Write-Host "     git tag $Tag" -ForegroundColor White
Write-Host "     git push origin $Tag" -ForegroundColor White
Write-Host "  2. GitHub CLI でログインして実行:" -ForegroundColor Cyan
Write-Host "     .\.tools\bin\gh.exe auth login" -ForegroundColor White
Write-Host "     .\upload_release.ps1" -ForegroundColor White
Write-Host "==========================================================" -ForegroundColor Yellow
