# BIO Alinity App - namestitev na priklopljeno tablico (USB debug)
# Uporaba:  powershell -ExecutionPolicy Bypass -File install-tablet.ps1

$ErrorActionPreference = "Stop"

# 1) Poisci adb
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) {
  $c = @(
    "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
    "$env:USERPROFILE\Downloads\platform-tools\adb.exe",
    "C:\Users\aljaz\Downloads\platform-tools\adb.exe"
  ) | Where-Object { Test-Path $_ } | Select-Object -First 1
  $adb = $c
}
if (-not $adb) { Write-Host "NAPAKA: adb.exe ni najden." -ForegroundColor Red; exit 1 }
Write-Host "adb: $adb"

# 2) Poisci APK (najprej lokalni build, sicer preneseni artifact)
$apk = @(
  "app\build\outputs\apk\debug\app-debug.apk",
  "$env:USERPROFILE\Downloads\bio-alinity-apk\app-debug.apk"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $apk) { Write-Host "NAPAKA: app-debug.apk ni najden." -ForegroundColor Red; exit 1 }
Write-Host "APK: $apk"

# 3) Cakaj na napravo (avtoriziraj poziv na tablici!)
& $adb start-server | Out-Null
Write-Host "Cakam na napravo... (na tablici potrdi 'Allow USB debugging')"
& $adb wait-for-device
$serial = (& $adb devices | Select-String "device$" | ForEach-Object { ($_ -split "\s+")[0] } | Select-Object -First 1)
if (-not $serial) { Write-Host "NAPAKA: naprava ni avtorizirana ali ni vidna." -ForegroundColor Red; & $adb devices; exit 1 }
Write-Host "Naprava: $serial"

# 4) Namesti
Write-Host "Namescam..."
& $adb -s $serial install -r $apk
Write-Host "Koncano. Zazeni 'BIO Alinity App' na tablici." -ForegroundColor Green
