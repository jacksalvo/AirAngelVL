param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string[]]$Tasks = @(':app:assembleUsbOnlyDebug', ':app:assembleUsbOnlyRelease', ':app:bundleUsbOnlyRelease'),
    [switch]$Verify,
    [switch]$Validation
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
if ($Validation -and $Verify) { throw 'Run shipping -Verify and emulator -Validation as separate builds.' }
if ($Validation -and !$PSBoundParameters.ContainsKey('Tasks')) { $Tasks = @(':app:assembleUsbOnlyDebug') }
if (!$JavaHome) {
    $portableJdk = 'C:\Coding\AAVL-toolchains\jdk-17.0.20.1+1'
    if (Test-Path -LiteralPath $portableJdk) { $JavaHome = $portableJdk }
}
if (!$JavaHome -or !(Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Provide -JavaHome pointing to JDK 17, or set JAVA_HOME.'
}
$release = Get-Content -LiteralPath (Join-Path $JavaHome 'release') -Raw
if ($release -notmatch 'JAVA_VERSION="17\.') { throw 'This build requires JDK 17.' }
if (!$SdkRoot -and (Test-Path -LiteralPath 'C:\Android\Sdk')) { $SdkRoot = 'C:\Android\Sdk' }
if (!$SdkRoot) { throw 'Provide -SdkRoot or set ANDROID_HOME.' }

function Copy-PreservedArtifact {
    param([string]$Source, [string]$Destination)
    if (Test-Path -LiteralPath $Destination) {
        $oldHash = (Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash.ToLowerInvariant()
        $newHash = (Get-FileHash -LiteralPath $Source -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($oldHash -eq $newHash) { return }
        # Keep earlier builds before refreshing convenient artifact aliases.
        $archiveDirectory = Join-Path (Split-Path -Parent $Destination) 'archive'
        New-Item -ItemType Directory -Path $archiveDirectory -Force | Out-Null
        $oldName = [IO.Path]::GetFileNameWithoutExtension($Destination) + '-' + $oldHash + [IO.Path]::GetExtension($Destination)
        $archivePath = Join-Path $archiveDirectory $oldName
        if (!(Test-Path -LiteralPath $archivePath)) {
            Copy-Item -LiteralPath $Destination -Destination $archivePath
        }
    }
    Copy-Item -LiteralPath $Source -Destination $Destination -Force
}

$originalJava = $env:JAVA_HOME
$originalAndroid = $env:ANDROID_HOME
$originalJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $SdkRoot
    # This Windows host rejects AF_UNIX connects. An intentionally absent socket
    # directory makes the JDK's built-in PipeImpl fallback use TCP loopback.
    # Limit the workaround to this build process and its children.
    $unixSocketDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ('aavl-tcp-' + [Guid]::NewGuid().ToString('N'))
    $env:JAVA_TOOL_OPTIONS = $originalJavaOptions + ' "-Djdk.net.unixdomain.tmpdir=' + $unixSocketDirectory + '"'
    # Java property files require escaped drive separators. Use forward slashes
    # for Windows paths and LF/UTF-8 without a BOM so lint and Gradle agree.
    # This machine-local file is ignored by version control.
    $sdkProperty = 'sdk.dir=' + $SdkRoot.Replace('\', '/').Replace(':', '\:')
    [IO.File]::WriteAllText((Join-Path $repoRoot 'local.properties'), $sdkProperty + "`n", (New-Object System.Text.UTF8Encoding $false))
    Push-Location $repoRoot
    try {
        if ($Verify) {
            $Tasks = @(':app:assembleUsbOnlyDebug', ':app:assembleUsbOnlyRelease', ':app:bundleUsbOnlyRelease',
                ':app:lintUsbOnlyDebug', ':app:testUsbOnlyDebugUnitTest',
                ':camera-usb:testUsbOnlyDebugUnitTest', ':core:testDebugUnitTest',
                ':diagnostics:testUsbOnlyDebugUnitTest')
        }
        $buildArguments = @($Tasks) + @('--no-daemon', '--no-configuration-cache')
        if ($Verify) { $buildArguments += '--continue' }
        if ($Validation) { $buildArguments += '-PvalidationAbis=x86_64' }
        & (Join-Path $repoRoot 'gradlew.bat') @buildArguments
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed (exit $LASTEXITCODE)." }
        $artifactDirectory = Join-Path $repoRoot 'artifacts'
        New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
        if ($Tasks -contains ':app:assembleUsbOnlyDebug') {
            $debugName = if ($Validation) { 'app-usbOnly-x86_64-validation-debug.apk' } else { 'app-usbOnly-debug.apk' }
            Copy-PreservedArtifact -Source (Join-Path $repoRoot 'app\build\outputs\apk\usbOnly\debug\app-usbOnly-debug.apk') -Destination (Join-Path $artifactDirectory $debugName)
            $metadata = Get-Content -LiteralPath (Join-Path $repoRoot 'app\build\outputs\apk\usbOnly\debug\output-metadata.json') -Raw | ConvertFrom-Json
            $version = $metadata.elements[0].versionName
            $versionedName = if ($Validation) { "AirAngelVL-$version-validation-x86_64.apk" } else { "AirAngelVL-$version-debug-universal.apk" }
            Copy-PreservedArtifact -Source (Join-Path $artifactDirectory $debugName) -Destination (Join-Path $artifactDirectory $versionedName)
        }
        if ($Tasks -contains ':app:assembleUsbOnlyRelease') {
            $releaseDirectory = Join-Path $repoRoot 'app\build\outputs\apk\usbOnly\release'
            $metadata = Get-Content -LiteralPath (Join-Path $releaseDirectory 'output-metadata.json') -Raw | ConvertFrom-Json
            $output = $metadata.elements[0]
            $version = $output.versionName
            $releaseName = $output.outputFile
            $releaseSuffix = if ($releaseName -like '*-unsigned.apk') { 'release-unsigned-universal' } else { 'release-universal' }
            Copy-PreservedArtifact -Source (Join-Path $releaseDirectory $releaseName) -Destination (Join-Path $artifactDirectory $releaseName)
            Copy-PreservedArtifact -Source (Join-Path $artifactDirectory $releaseName) -Destination (Join-Path $artifactDirectory "AirAngelVL-$version-$releaseSuffix.apk")
        }
        if ($Tasks -contains ':app:bundleUsbOnlyRelease') {
            $bundlePath = Join-Path $repoRoot 'app\build\outputs\bundle\usbOnlyRelease\app-usbOnly-release.aab'
            $bundleHash = (Get-FileHash -LiteralPath $bundlePath -Algorithm SHA256).Hash.ToLowerInvariant()
            Copy-PreservedArtifact -Source $bundlePath -Destination (Join-Path $artifactDirectory 'app-usbOnly-release.aab')
            Copy-PreservedArtifact -Source $bundlePath -Destination (Join-Path $artifactDirectory ("AirAngelVL-play-release-" + $bundleHash.Substring(0, 12) + '.aab'))
            Write-Output 'App bundle generated. Verify native packaging and the intended upload signature before uploading to Google Play.'
        }
        Get-ChildItem -LiteralPath $artifactDirectory -File | Where-Object { $_.Extension -in @('.apk', '.aar', '.aab') } | Sort-Object Name | ForEach-Object {
            $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            "$hash  $($_.Name)"
        } | Set-Content -LiteralPath (Join-Path $artifactDirectory 'SHA256SUMS.txt') -Encoding ascii
    } finally { Pop-Location }
} finally {
    $env:JAVA_HOME = $originalJava
    $env:ANDROID_HOME = $originalAndroid
    $env:JAVA_TOOL_OPTIONS = $originalJavaOptions
}
