$ErrorActionPreference = "Stop"

$packageName = "by.innowise.trainchecker"
$backupDir = Join-Path $PSScriptRoot "backups"
$backupPath = Join-Path $backupDir "trainchecker-data.tar"

New-Item -ItemType Directory -Force $backupDir | Out-Null

& adb shell "run-as $packageName pwd" | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Cannot access app data with run-as. Make sure the installed app is debuggable."
}

$cmd = "adb exec-out run-as $packageName sh -c ""cd /data/user/0/$packageName && toybox tar -cf - ."" > ""$backupPath"""
& cmd /c $cmd
if ($LASTEXITCODE -ne 0) {
    throw "Failed to create app data backup."
}

$backup = Get-Item $backupPath
if ($backup.Length -le 0) {
    throw "Backup file is empty: $backupPath"
}

& tar -tf $backupPath | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Backup archive is not readable: $backupPath"
}

Write-Host "App data backup saved to $backupPath ($($backup.Length) bytes)."
