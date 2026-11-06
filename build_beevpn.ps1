$ErrorActionPreference = "Stop"

$rootDir = "C:\Users\Bisrat\Documents\APPS\Psiphon"
$toolsDir = "$rootDir\tools"
$appDir = "$rootDir\beevpn_app"
$javaExe = "$toolsDir\jre\bin\java.exe"

Write-Output "=== Step 1: Cleaning previous build artifacts ==="
Remove-Item -Path "$appDir\gen", "$appDir\bin", "$appDir\dex", "$rootDir\compiled_res.zip", "$rootDir\beevpn_unaligned.apk", "$rootDir\*aligned-debugSigned.apk", "$rootDir\BeeVpn.apk" -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$appDir\gen" | Out-Null
New-Item -ItemType Directory -Force -Path "$appDir\bin" | Out-Null
New-Item -ItemType Directory -Force -Path "$appDir\dex" | Out-Null

Write-Output "=== Step 2: Compiling Android Resources with AAPT2 ==="
& "$toolsDir\aapt2.exe" compile --dir "$appDir\res" -o "$rootDir\compiled_res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Output "=== Step 3: Linking Resources & Generating R.java ==="
& "$toolsDir\aapt2.exe" link -I "$toolsDir\android.jar" --manifest "$appDir\AndroidManifest.xml" -o "$rootDir\beevpn_unaligned.apk" "$rootDir\compiled_res.zip" --java "$appDir\gen" --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Output "=== Step 4: Compiling Java Sources with ECJ ==="
$javaFiles = @(Get-ChildItem -Path "$appDir\src", "$appDir\gen" -Filter "*.java" -Recurse | ForEach-Object { $_.FullName })
& $javaExe -jar "$toolsDir\ecj.jar" -8 -cp "$toolsDir\android.jar;$toolsDir\classes.jar" -d "$appDir\bin" $javaFiles
if ($LASTEXITCODE -ne 0) { throw "ecj compilation failed" }

Write-Output "=== Step 5: Generating Dalvik Bytecode (classes.dex) with D8 ==="
$classFiles = @(Get-ChildItem -Path "$appDir\bin" -Filter "*.class" -Recurse | ForEach-Object { $_.FullName })
& $javaExe -cp "$toolsDir\r8.jar" com.android.tools.r8.D8 --lib "$toolsDir\android.jar" --min-api 21 --output "$appDir\dex" $classFiles "$toolsDir\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 compilation failed" }

Write-Output "=== Step 6: Packaging classes.dex and native libraries into APK ==="
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$rootDir\beevpn_unaligned.apk", [System.IO.Compression.ZipArchiveMode]::Update)
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$appDir\dex\classes.dex", "classes.dex") | Out-Null

$soFiles = Get-ChildItem -Path "$appDir\lib" -Recurse -Filter "*.so"
foreach ($so in $soFiles) {
    $entryName = "lib/" + $so.FullName.Substring("$appDir\lib\".Length).Replace("\", "/")
    Write-Output "Adding native library: $entryName"
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $so.FullName, $entryName) | Out-Null
}
$zip.Dispose()

Write-Output "=== Step 7: Aligning & Cryptographically Signing BeeVpn.apk ==="
& $javaExe -jar "$toolsDir\uber-apk-signer.jar" -a "$rootDir\beevpn_unaligned.apk" -o "$rootDir"
if ($LASTEXITCODE -ne 0) { throw "signing failed" }

# Rename output to clean BeeVpn.apk
$signedApk = Get-ChildItem -Path "$rootDir" -Filter "*aligned-debugSigned.apk" | Select-Object -First 1
if ($signedApk) {
    Copy-Item -Path $signedApk.FullName -Destination "$rootDir\BeeVpn.apk" -Force
    Remove-Item -Path $signedApk.FullName -Force
}

Write-Output "`n==============================================="
Write-Output "SUCCESS! BeeVpn.apk successfully built locally!"
Write-Output "Output: $rootDir\BeeVpn.apk"
Write-Output "Size: $((Get-Item "$rootDir\BeeVpn.apk").Length) bytes"
Write-Output "==============================================="
