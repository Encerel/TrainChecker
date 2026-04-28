$ErrorActionPreference = "Stop"

$packageName = "by.innowise.trainchecker"
$backupScript = Join-Path $PSScriptRoot "backup-app-data.ps1"
$restoreScript = Join-Path $PSScriptRoot "restore-app-data.ps1"
$gradlew = Join-Path (Resolve-Path "$PSScriptRoot\..") "gradlew.bat"

& $backupScript
if ($LASTEXITCODE -ne 0) {
    throw "Backup failed, reinstall aborted."
}

& adb uninstall $packageName
if ($LASTEXITCODE -ne 0) {
    throw "Failed to uninstall $packageName."
}

& $gradlew ":app:installDebug"
if ($LASTEXITCODE -ne 0) {
    throw "Failed to install debug APK."
}

& $restoreScript
if ($LASTEXITCODE -ne 0) {
    throw "Restore failed after reinstall."
}

Write-Host "Debug APK reinstalled and app data restored."
