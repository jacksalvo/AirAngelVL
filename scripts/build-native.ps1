param(
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$OutputDirectory,
    [int]$Jobs = 8,
    [string]$Abis = 'armeabi-v7a,arm64-v8a'
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (!$SdkRoot) {
    $sdkLine = Get-Content -LiteralPath (Join-Path $repoRoot 'local.properties') |
        Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
    if (!$sdkLine) { throw 'Set ANDROID_HOME or sdk.dir in local.properties.' }
    $SdkRoot = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
}
$ndkRoot = Join-Path $SdkRoot 'ndk\27.0.12077973'
if (!(Test-Path -LiteralPath (Join-Path $ndkRoot 'ndk-build.cmd'))) {
    throw 'Install NDK 27.0.12077973 with the Android SDK Manager.'
}
if (!$OutputDirectory) { $OutputDirectory = Join-Path $repoRoot 'native-uvc\build\generated\jniLibs' }
$objectDirectory = Join-Path $repoRoot 'native-uvc\build\native-obj'
New-Item -ItemType Directory -Path $OutputDirectory, $objectDirectory -Force | Out-Null

# GNU make does not reliably accept spaces in NDK paths. Windows short paths
# reference the same source files and avoid copying or modifying the vendored tree.
Add-Type -TypeDefinition @'
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class AavlNativePath {
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    public static extern uint GetShortPathName(string path, StringBuilder output, uint length);
}
'@
function Get-NdkPath([string]$Path) {
    $resolved = (Resolve-Path -LiteralPath $Path).Path
    $buffer = New-Object System.Text.StringBuilder 32768
    if ([AavlNativePath]::GetShortPathName($resolved, $buffer, $buffer.Capacity) -eq 0) {
        throw "Cannot resolve NDK path: $resolved"
    }
    $short = $buffer.ToString().Replace('\', '/')
    if ($short -match '\s') {
        throw "NDK requires a path without spaces when Windows short names are disabled: $resolved"
    }
    return $short
}
$sourceRoot = Get-NdkPath (Join-Path $repoRoot 'external\AndroidUSBCamera\libuvc\src\main')
$objects = Get-NdkPath $objectDirectory
$libraries = Get-NdkPath $OutputDirectory
$ndk = Get-NdkPath $ndkRoot
$selectedAbis = $Abis.Split(',')
foreach ($abi in $selectedAbis) {
    if ($abi -notin @('armeabi-v7a', 'arm64-v8a', 'x86_64')) { throw "Unsupported build ABI: $abi" }
    & "$ndk/ndk-build.cmd" "-j$Jobs" "NDK_PROJECT_PATH=$sourceRoot" `
        "APP_BUILD_SCRIPT=$sourceRoot/jni/Android.mk" `
        "NDK_APPLICATION_MK=$sourceRoot/jni/Application.mk" `
        "NDK_OUT=$objects" "NDK_LIBS_OUT=$libraries" "APP_ABI=$abi"
    if ($LASTEXITCODE -ne 0) { throw "Native build failed for $abi (exit $LASTEXITCODE)." }
}
Write-Output "Built UVC from source for $Abis in $OutputDirectory"
