param(
    [Parameter(Mandatory = $true)][string]$Bundle,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$Bundletool
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if (!$JavaHome -and (Test-Path -LiteralPath 'C:\Coding\AAVL-toolchains\jdk-17.0.20.1+1')) {
    $JavaHome = 'C:\Coding\AAVL-toolchains\jdk-17.0.20.1+1'
}
if (!$JavaHome) { throw 'Provide -JavaHome pointing to JDK 17, or set JAVA_HOME.' }
$java = Join-Path $JavaHome 'bin\java.exe'
if (!(Test-Path -LiteralPath $java)) { throw 'Java executable not found.' }
if (!$SdkRoot -and (Test-Path -LiteralPath 'C:\Android\Sdk')) { $SdkRoot = 'C:\Android\Sdk' }
$bundlePath = (Resolve-Path -LiteralPath $Bundle).Path
$bundletoolVersion = '1.18.3'
$bundletoolHash = 'a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29'
if (!$Bundletool) {
    $toolchainDirectory = Join-Path $repoRoot '.toolchains'
    New-Item -ItemType Directory -Path $toolchainDirectory -Force | Out-Null
    $Bundletool = Join-Path $toolchainDirectory "bundletool-all-$bundletoolVersion.jar"
    if (!(Test-Path -LiteralPath $Bundletool)) {
        $download = "https://github.com/google/bundletool/releases/download/$bundletoolVersion/bundletool-all-$bundletoolVersion.jar"
        Invoke-WebRequest -UseBasicParsing -Uri $download -OutFile $Bundletool
    }
}
if ((Get-FileHash -LiteralPath $Bundletool -Algorithm SHA256).Hash.ToLowerInvariant() -ne $bundletoolHash) {
    throw 'Bundletool checksum mismatch; the downloaded tool was not executed.'
}
$Bundletool = (Resolve-Path -LiteralPath $Bundletool).Path
$verificationRoot = Join-Path $repoRoot 'artifacts\bundle-verification'
$verificationDirectory = Join-Path $verificationRoot ((Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $verificationDirectory -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem

# AAB ZIP offsets are not installed on-device. Check the requested alignment,
# then inspect an APK generated from this exact bundle using Google's tooling.
$originalJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $unixSocketDirectory = Join-Path ([IO.Path]::GetTempPath()) ('aavl-tcp-' + [Guid]::NewGuid().ToString('N'))
    $env:JAVA_TOOL_OPTIONS = $originalJavaOptions + ' "-Djdk.net.unixdomain.tmpdir=' + $unixSocketDirectory + '"'
    $structureReport = & $java -jar $Bundletool validate "--bundle=$bundlePath"
    $structureExit = $LASTEXITCODE
    $structureReport | Set-Content -LiteralPath (Join-Path $verificationDirectory 'bundle-structure.txt') -Encoding utf8
    if ($structureExit -ne 0) { throw "Bundletool structural validation failed; see $verificationDirectory." }
    Write-Output 'PASS bundletool structural validation'
    $config = & $java -jar $Bundletool dump config "--bundle=$bundlePath"
    if ($LASTEXITCODE -ne 0) { throw 'Bundletool config inspection failed.' }
    $config | Set-Content -LiteralPath (Join-Path $verificationDirectory 'bundle-config.json') -Encoding utf8
    if (($config -join "`n") -notmatch 'PAGE_ALIGNMENT_16K') {
        throw 'The bundle does not request 16 KB native-library ZIP alignment.'
    }
    $manifest = & $java -jar $Bundletool dump manifest "--bundle=$bundlePath" --module=base
    if ($LASTEXITCODE -ne 0) { throw 'Bundletool manifest inspection failed.' }
    $manifest | Set-Content -LiteralPath (Join-Path $verificationDirectory 'AndroidManifest.xml') -Encoding utf8
    [xml]$manifestXml = $manifest -join "`n"
    $androidNamespace = 'http://schemas.android.com/apk/res/android'
    if ($manifestXml.manifest.package -ne 'com.airangelvl') { throw 'Unexpected production package.' }
    $usesSdk = $manifestXml.manifest.'uses-sdk'
    if ($usesSdk.GetAttribute('minSdkVersion', $androidNamespace) -ne '24') { throw 'Minimum Android API changed.' }
    if ([int]$usesSdk.GetAttribute('targetSdkVersion', $androidNamespace) -lt 36) { throw 'Google Play bundle must target API 36 or higher.' }
    if ($manifestXml.manifest.application.GetAttribute('debuggable', $androidNamespace) -eq 'true') { throw 'Production bundle is debuggable.' }

    $bundleArchive = [IO.Compression.ZipFile]::OpenRead($bundlePath)
    try {
        $expected = @('armeabi-v7a', 'arm64-v8a') | ForEach-Object {
            $abi = $_
            @('libUVCCamera.so', 'libuvc.so', 'libusb100.so', 'libjpeg-turbo1500.so', 'libdatastore_shared_counter.so') | ForEach-Object { "base/lib/$abi/$_" }
        }
        $actual = @($bundleArchive.Entries | Where-Object { $_.FullName -match '\.so$' } | ForEach-Object { $_.FullName })
        if ($actual.Count -ne $expected.Count -or (Compare-Object ($actual | Sort-Object) ($expected | Sort-Object))) {
            throw 'Unexpected native-library inventory in the app bundle.'
        }
    } finally { $bundleArchive.Dispose() }

    $apkSetPath = Join-Path $verificationDirectory 'derived-universal.apks'
    & $java -jar $Bundletool build-apks "--bundle=$bundlePath" "--output=$apkSetPath" --mode=universal
    if ($LASTEXITCODE -ne 0) { throw 'Generating an APK from the bundle failed.' }
    $apkSet = [IO.Compression.ZipFile]::OpenRead($apkSetPath)
    $apkPath = Join-Path $verificationDirectory 'derived-universal.apk'
    try {
        $entry = $apkSet.GetEntry('universal.apk')
        if (!$entry) { throw 'Bundletool did not generate universal.apk.' }
        [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $apkPath)
    } finally { $apkSet.Dispose() }
    & (Join-Path $PSScriptRoot 'verify-native.ps1') -Apk $apkPath -SdkRoot $SdkRoot
    Write-Output "PASS bundle structure, release manifest, both ARM ABIs and 16 KB generated-APK alignment: $verificationDirectory"
    Write-Output 'The derived APK uses a local debug key for packaging validation. It is not a signed production release; verify the bundle upload signature separately.'
} finally { $env:JAVA_TOOL_OPTIONS = $originalJavaOptions }
