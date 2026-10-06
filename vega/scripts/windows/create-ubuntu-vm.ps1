<#
.SYNOPSIS
  Vega SDK 用の Ubuntu 22.04 VM を Hyper-V に作成する (Windows 11 Pro)。
.DESCRIPTION
  管理者 PowerShell で実行する。Hyper-V が未有効なら有効化して再起動を促す (再起動後にもう一度実行)。
  ネットワークは Hyper-V の Default Switch (NAT)。Wi-Fi 接続の PC でもブリッジより安定する。
  ISO (約2.1GB) をダウンロードして VM を作り、インストーラで起動する。OS のインストール自体は手動。
  Ubuntu 22.04 を選ぶ理由: Vega が要求する Python 3.8 を deadsnakes PPA から入れられる (24.04 では不可の可能性)。
#>
[CmdletBinding()]
param(
  [string]$VmName = 'vega-dev',
  [string]$VmRoot = 'E:\VM',
  [int]$CpuCount = 8,
  [long]$MemoryGB = 16,
  [long]$DiskGB = 120,
  [string]$IsoUrl = 'https://releases.ubuntu.com/22.04/ubuntu-22.04.5-live-server-amd64.iso'
)
$ErrorActionPreference = 'Stop'

$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) { throw '管理者として実行してください。' }

$feature = Get-WindowsOptionalFeature -Online -FeatureName Microsoft-Hyper-V-All
if ($feature.State -ne 'Enabled') {
  Enable-WindowsOptionalFeature -Online -FeatureName Microsoft-Hyper-V-All -NoRestart | Out-Null
  Write-Host 'Hyper-V を有効化しました。PC を再起動してから、このスクリプトをもう一度実行してください。' -ForegroundColor Yellow
  return
}

if (Get-VM -Name $VmName -ErrorAction SilentlyContinue) { throw "VM '$VmName' は既にあります。" }

New-Item -ItemType Directory -Force -Path $VmRoot | Out-Null
$iso  = Join-Path $VmRoot (Split-Path $IsoUrl -Leaf)
$vhdx = Join-Path $VmRoot "$VmName.vhdx"

if (-not (Test-Path $iso)) {
  Write-Host "ISO をダウンロード: $IsoUrl"
  Start-BitsTransfer -Source $IsoUrl -Destination $iso
}

$vm = New-VM -Name $VmName -Generation 2 -MemoryStartupBytes ($MemoryGB * 1GB) `
  -NewVHDPath $vhdx -NewVHDSizeBytes ($DiskGB * 1GB) -SwitchName 'Default Switch' -Path $VmRoot
Set-VMProcessor -VMName $VmName -Count $CpuCount -ExposeVirtualizationExtensions $true  # 入れ子の KVM (Virtual Device 用)
Set-VMMemory -VMName $VmName -DynamicMemoryEnabled $false
Set-VMFirmware -VMName $VmName -SecureBootTemplate 'MicrosoftUEFICertificateAuthority'  # Ubuntu 用
Set-VM -VMName $VmName -CheckpointType Standard -AutomaticCheckpointsEnabled $false
Enable-VMIntegrationService -VMName $VmName -Name 'Guest Service Interface'

$dvd = Add-VMDvdDrive -VMName $VmName -Path $iso -Passthru
Set-VMFirmware -VMName $VmName -FirstBootDevice $dvd

Start-VM -Name $VmName
vmconnect.exe localhost $VmName
Write-Host @"
VM '$VmName' を起動しました。インストーラで次を選んでください:
  - Ubuntu Server (minimized ではない通常版) / "Install OpenSSH server" にチェック
  - ディスクは全体を使用 (LVM のままで可)
  - ユーザー名は任意。パスワードは自分で決めて控える
インストール後に再起動し、ISO を取り外してください:
  Get-VMDvdDrive -VMName $VmName | Remove-VMDvdDrive
IP の確認: (Get-VM $VmName).NetworkAdapters.IPAddresses
"@
