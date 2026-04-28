$ErrorActionPreference = "Stop"

$packageName = "by.innowise.trainchecker"
$backupPath = Join-Path $PSScriptRoot "backups\trainchecker-data.tar"
$deviceBackupPath = "/data/local/tmp/trainchecker-data.tar"

if (-not (Test-Path $backupPath)) {
    throw "Backup file not found: $backupPath"
}

& adb push $backupPath $deviceBackupPath
if ($LASTEXITCODE -ne 0) {
    throw "Failed to push backup to device."
}

& adb shell chmod 644 $deviceBackupPath
if ($LASTEXITCODE -ne 0) {
    throw "Failed to prepare backup permissions on device."
}

& adb shell "run-as $packageName sh -c 'cd /data/user/0/$packageName && toybox tar -xf $deviceBackupPath'"
if ($LASTEXITCODE -ne 0) {
    & adb shell rm $deviceBackupPath | Out-Null
    throw "Failed to restore app data."
}

& adb shell rm $deviceBackupPath | Out-Null
& adb shell am force-stop $packageName | Out-Null

Write-Host "App data restored for $packageName."
