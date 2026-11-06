$toolsDir = "C:\Users\Bisrat\Documents\APPS\Psiphon\tools"
New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null

$downloads = @(
    @{ Name = "aapt2.jar"; Url = "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.2.2-10154469/aapt2-8.2.2-10154469-windows.jar" },
    @{ Name = "android.jar"; Url = "https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar" },
    @{ Name = "r8.jar"; Url = "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.42/r8-8.2.42.jar" },
    @{ Name = "ecj.jar"; Url = "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.26.0/ecj-3.26.0.jar" },
    @{ Name = "uber-apk-signer.jar"; Url = "https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar" }
)

foreach ($d in $downloads) {
    $dest = Join-Path $toolsDir $d.Name
    if (-not (Test-Path $dest)) {
        Write-Output "Downloading $($d.Name)..."
        curl.exe --ssl-no-revoke -L -o $dest $d.Url
    } else {
        Write-Output "$($d.Name) already exists."
    }
}

# Extract aapt2.exe from aapt2.jar
if (-not (Test-Path "$toolsDir\aapt2.exe")) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead("$toolsDir\aapt2.jar")
    $entry = $zip.Entries | Where-Object { $_.FullName -like "*aapt2.exe*" } | Select-Object -First 1
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, "$toolsDir\aapt2.exe", $true)
    $zip.Dispose()
    Write-Output "Extracted aapt2.exe successfully!"
}

Write-Output "=== Tools downloaded and ready ==="
Get-ChildItem -Path $toolsDir | Select-Object Name, Length
