# SPDX-License-Identifier: GPL-2.0-only
param(
    [Parameter(Mandatory = $true)][string]$PropertiesFile,
    [string]$InputApk = "$PSScriptRoot/app/build/outputs/apk/release/app-release.apk",
    [string]$OutputApk = "$PSScriptRoot/app/build/outputs/apk/release/AppTime-v1.3.1.apk"
)

$ErrorActionPreference = 'Stop'
if (-not $env:ANDROID_HOME) { throw 'ANDROID_HOME is required.' }
$taskSigner = Join-Path $env:ANDROID_HOME 'build-tools/36.0.0/apksigner.bat'
$taskLineage = Join-Path $PSScriptRoot 'signing-lineage.bin'
foreach ($taskPath in @($PropertiesFile, $InputApk, $taskSigner, $taskLineage)) {
    if (-not (Test-Path -LiteralPath $taskPath)) { throw "Missing file: $taskPath" }
}
$taskProperties = @{}
foreach ($taskLine in [IO.File]::ReadAllLines($PropertiesFile)) {
    if ($taskLine -match '^\s*([^#!\s=]+)\s*=\s*(.*)$') { $taskProperties[$Matches[1]] = $Matches[2] }
}
foreach ($taskName in @('storeFile', 'storePassword', 'keyAlias', 'keyPassword')) {
    if (-not $taskProperties[$taskName]) { throw "Missing signing property: $taskName" }
}

try {
    $env:APPTIME_STORE_PASSWORD = $taskProperties['storePassword']
    $env:APPTIME_KEY_PASSWORD = $taskProperties['keyPassword']
    & $taskSigner sign --ks $taskProperties['storeFile'] --ks-type PKCS12 --ks-key-alias $taskProperties['keyAlias'] `
        --ks-pass env:APPTIME_STORE_PASSWORD --key-pass env:APPTIME_KEY_PASSWORD `
        --lineage $taskLineage --rotation-min-sdk-version 28 `
        --v1-signing-enabled false --v2-signing-enabled false --v3-signing-enabled true `
        --out $OutputApk $InputApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
    & $taskSigner verify --min-sdk-version 29 --verbose $OutputApk
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
}
finally {
    Remove-Item Env:APPTIME_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:APPTIME_KEY_PASSWORD -ErrorAction SilentlyContinue
}

$taskHash = (Get-FileHash -LiteralPath $OutputApk -Algorithm SHA256).Hash.ToLowerInvariant()
$taskSum = Join-Path (Split-Path -Parent $OutputApk) 'SHA256SUMS.txt'
[IO.File]::WriteAllText($taskSum, "$taskHash  $(Split-Path -Leaf $OutputApk)`n", [Text.UTF8Encoding]::new($false))
Write-Output "Release APK: $OutputApk"
Write-Output "SHA256: $taskHash"
