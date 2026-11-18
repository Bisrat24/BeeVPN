param(
    [switch]$Release,
    [string]$Keystore = $env:BEEVPN_KEYSTORE,
    [string]$StorePass = $env:BEEVPN_STOREPASS,
    [string]$KeyAlias = $env:BEEVPN_KEYALIAS
)

$ErrorActionPreference = "Stop"

# Derive every path from this script so the build works from any checkout location.
$rootDir = $PSScriptRoot
$toolsDir = Join-Path $rootDir "tools"
$appDir = Join-Path $rootDir "beevpn_app"
$javaExe = Join-Path $toolsDir "jre\bin\java.exe"

# Single source of truth for the version: AndroidManifest.xml
[xml]$manifest = Get-Content (Join-Path $appDir "AndroidManifest.xml")
$androidNs = "http://schemas.android.com/apk/res/android"
$versionName = $manifest.DocumentElement.GetAttribute("versionName", $androidNs)
$versionCode = $manifest.DocumentElement.GetAttribute("versionCode", $androidNs)
Write-Output "=== BeeVPN $versionName (versionCode $versionCode) ==="

Write-Output "=== Step 1: Cleaning previous build artifacts ==="
Remove-Item -Path (Join-Path $appDir "gen"), (Join-Path $appDir "bin"), (Join-Path $appDir "dex"), `
    (Join-Path $rootDir "compiled_res.zip"), (Join-Path $rootDir "beevpn_unaligned.apk"), `
    (Join-Path $rootDir "*aligned*.apk"), (Join-Path $rootDir "BeeVpn.apk") `
    -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path (Join-Path $appDir "gen") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $appDir "bin") | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $appDir "dex") | Out-Null

Write-Output "=== Step 2: Compiling Android Resources with AAPT2 ==="
& (Join-Path $toolsDir "aapt2.exe") compile --dir (Join-Path $appDir "res") -o (Join-Path $rootDir "compiled_res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Output "=== Step 3: Linking Resources & Generating R.java ==="
& (Join-Path $toolsDir "aapt2.exe") link `
    -I (Join-Path $toolsDir "android.jar") `
    --manifest (Join-Path $appDir "AndroidManifest.xml") `
    -o (Join-Path $rootDir "beevpn_unaligned.apk") `
    (Join-Path $rootDir "compiled_res.zip") `
    --java (Join-Path $appDir "gen") --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Output "=== Step 4: Compiling Java Sources with ECJ ==="
$javaFiles = @(Get-ChildItem -Path (Join-Path $appDir "src"), (Join-Path $appDir "gen") -Filter "*.java" -Recurse | ForEach-Object { $_.FullName })
& $javaExe -jar (Join-Path $toolsDir "ecj.jar") -8 `
    -cp "$($toolsDir)\android.jar;$($toolsDir)\classes.jar" `
    -d (Join-Path $appDir "bin") $javaFiles
if ($LASTEXITCODE -ne 0) { throw "ecj compilation failed" }

Write-Output "=== Step 5: Generating Dalvik Bytecode (classes.dex) with D8 ==="
$classFiles = @(Get-ChildItem -Path (Join-Path $appDir "bin") -Filter "*.class" -Recurse | ForEach-Object { $_.FullName })
& $javaExe -cp (Join-Path $toolsDir "r8.jar") com.android.tools.r8.D8 `
    --lib (Join-Path $toolsDir "android.jar") --min-api 21 `
    --output (Join-Path $appDir "dex") $classFiles (Join-Path $toolsDir "classes.jar")
if ($LASTEXITCODE -ne 0) { throw "d8 compilation failed" }

Write-Output "=== Step 6: Packaging classes.dex and native libraries into APK ==="
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$unaligned = Join-Path $rootDir "beevpn_unaligned.apk"
$zip = [System.IO.Compression.ZipFile]::Open($unaligned, [System.IO.Compression.ZipArchiveMode]::Update)
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $appDir "dex\classes.dex"), "classes.dex") | Out-Null

$soFiles = Get-ChildItem -Path (Join-Path $appDir "lib") -Recurse -Filter "*.so"
foreach ($so in $soFiles) {
    $entryName = "lib/" + $so.FullName.Substring((Join-Path $appDir "lib").Length + 1).Replace("\", "/")
    Write-Output "Adding native library: $entryName"
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $so.FullName, $entryName) | Out-Null
}
$zip.Dispose()

Write-Output "=== Step 7: Aligning & Signing BeeVPN.apk ==="
$signArgs = @("-a", $unaligned, "-o", $rootDir)
if ($Release) {
    if ([string]::IsNullOrWhiteSpace($Keystore) -or -not (Test-Path $Keystore)) {
        throw "Release signing requires -Keystore (or BEEVPN_KEYSTORE) pointing to a valid JKS keystore."
    }
    if ([string]::IsNullOrWhiteSpace($StorePass) -or [string]::IsNullOrWhiteSpace($KeyAlias)) {
        throw "Release signing requires -StorePass and -KeyAlias (or BEEVPN_STOREPASS / BEEVPN_KEYALIAS)."
    }
    $signArgs += @("--ks", $Keystore, "--ksPass", $StorePass, "--ksAlias", $KeyAlias)
    Write-Output "Signing with release keystore: $Keystore"
} else {
    $signArgs += "--debug"
    Write-Output "Signing with the development debug key (use -Release for distribution)."
}
& $javaExe -jar (Join-Path $toolsDir "uber-apk-signer.jar") $signArgs
if ($LASTEXITCODE -ne 0) { throw "signing failed" }

# uber-apk-signer names release output differently from debug output; pick the
# most recent signed APK that is not our final output or the unaligned input.
$signedApk = Get-ChildItem -Path $rootDir -Filter "*aligned*.apk" |
    Where-Object { $_.Name -ne "BeeVpn.apk" -and $_.Name -ne "beevpn_unaligned.apk" } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $signedApk) { throw "No signed APK was produced." }
Copy-Item -Path $signedApk.FullName -Destination (Join-Path $rootDir "BeeVpn.apk") -Force
Remove-Item -Path $signedApk.FullName -Force

Write-Output "`n==============================================="
Write-Output "SUCCESS! BeeVPN $versionName built successfully!"
Write-Output "Output: $(Join-Path $rootDir 'BeeVpn.apk')"
Write-Output "Size:   $((Get-Item (Join-Path $rootDir 'BeeVpn.apk')).Length) bytes"
Write-Output "==============================================="
