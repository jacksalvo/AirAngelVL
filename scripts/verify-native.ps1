param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [switch]$Validation
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apkPath = (Resolve-Path -LiteralPath $Apk).Path
$archive = [System.IO.Compression.ZipFile]::OpenRead($apkPath)
$expectedLibraries = @('libUVCCamera.so', 'libuvc.so', 'libusb100.so', 'libjpeg-turbo1500.so', 'libdatastore_shared_counter.so')
$seen = @{}
$expectedAbis = if ($Validation) { @('x86_64') } else { @('armeabi-v7a', 'arm64-v8a') }
try {
    foreach ($entry in $archive.Entries | Where-Object { $_.FullName -match '^lib/.+\.so$' }) {
        $parts = $entry.FullName.Split('/')
        $abi = $parts[1]
        if ($abi -notin $expectedAbis) { throw "Unexpected ABI: $abi" }
        if ($parts[2] -notin $expectedLibraries) { throw "Unexpected native dependency: $($entry.FullName)" }
        if ($seen.ContainsKey($entry.FullName)) { throw "Duplicate native library: $($entry.FullName)" }
        $seen[$entry.FullName] = $true
        $stream = $entry.Open()
        $memory = New-Object System.IO.MemoryStream
        try { $stream.CopyTo($memory); $bytes = $memory.ToArray() }
        finally { $stream.Dispose(); $memory.Dispose() }
        if ($bytes.Length -lt 64 -or $bytes[0] -ne 0x7f -or $bytes[1] -ne 0x45 -or $bytes[2] -ne 0x4c -or $bytes[3] -ne 0x46 -or $bytes[5] -ne 1) {
            throw "Invalid little-endian ELF: $($entry.FullName)"
        }
        $is64 = $bytes[4] -eq 2
        $machine = [BitConverter]::ToUInt16($bytes, 18)
        if (($abi -eq 'arm64-v8a' -and (!$is64 -or $machine -ne 183)) -or
            ($abi -eq 'armeabi-v7a' -and ($is64 -or $machine -ne 40)) -or
            ($abi -eq 'x86_64' -and (!$is64 -or $machine -ne 62))) {
            throw "ELF architecture mismatch: $($entry.FullName)"
        }
        if ($is64) {
            $offset = [BitConverter]::ToUInt64($bytes, 32)
            $size = [BitConverter]::ToUInt16($bytes, 54)
            $count = [BitConverter]::ToUInt16($bytes, 56)
        } else {
            $offset = [BitConverter]::ToUInt32($bytes, 28)
            $size = [BitConverter]::ToUInt16($bytes, 42)
            $count = [BitConverter]::ToUInt16($bytes, 44)
        }
        $loadCount = 0
        for ($i = 0; $i -lt $count; $i++) {
            $position = [int]($offset + $i * $size)
            if ([BitConverter]::ToUInt32($bytes, $position) -eq 1) {
                $loadCount++
                $alignment = if ($is64) { [BitConverter]::ToUInt64($bytes, $position + 48) }
                    else { [BitConverter]::ToUInt32($bytes, $position + 28) }
                if ($alignment -lt 16384) { throw "ELF is not aligned to 16 KB: $($entry.FullName)" }
            }
        }
        if (!$loadCount) { throw "ELF has no load segments: $($entry.FullName)" }
        Write-Output "PASS $($entry.FullName): architecture and 16 KB ELF alignment"
    }
    foreach ($abi in $expectedAbis) {
        foreach ($library in $expectedLibraries) {
            if (!$seen.ContainsKey("lib/$abi/$library")) { throw "Missing lib/$abi/$library" }
        }
    }
} finally { $archive.Dispose() }
if (!$SdkRoot -and (Test-Path -LiteralPath 'C:\Android\Sdk')) { $SdkRoot = 'C:\Android\Sdk' }
$zipalign = Join-Path $SdkRoot 'build-tools\35.0.0\zipalign.exe'
if (!(Test-Path -LiteralPath $zipalign)) { throw 'Install Android build-tools 35.0.0 for the 16 KB ZIP alignment check.' }
& $zipalign -c -P 16 4 $apkPath
if ($LASTEXITCODE -ne 0) { throw 'APK ZIP alignment check failed.' }
Write-Output 'PASS APK native library inventory and ZIP alignment'
