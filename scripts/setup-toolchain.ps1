param(
    [string]$ToolchainDirectory = 'C:\Coding\AAVL-toolchains',
    [string]$SdkRoot = 'C:\Android\Sdk'
)
$ErrorActionPreference = 'Stop'
$jdkName = 'jdk-17.0.20.1+1'
$archiveName = 'OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'
$expectedHash = 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0'
$download = 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/' + $archiveName
New-Item -ItemType Directory -Path $ToolchainDirectory -Force | Out-Null
$archive = Join-Path $ToolchainDirectory $archiveName
if (!(Test-Path -LiteralPath $archive)) { Invoke-WebRequest -Uri $download -OutFile $archive }
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expectedHash) {
    throw 'JDK archive checksum mismatch; the download was not extracted.'
}
$jdk = Join-Path $ToolchainDirectory $jdkName
if (!(Test-Path -LiteralPath $jdk)) { Expand-Archive -LiteralPath $archive -DestinationPath $ToolchainDirectory }
& (Join-Path $jdk 'bin\java.exe') -version
if ($LASTEXITCODE -ne 0) { throw 'JDK verification failed.' }
$requiredPackages = @('platforms\android-36', 'build-tools\35.0.0', 'ndk\27.0.12077973')
$missing = @($requiredPackages | Where-Object { !(Test-Path -LiteralPath (Join-Path $SdkRoot $_)) })
if ($missing.Count -gt 0) {
    throw ('Install these Android SDK packages using SDK Manager: ' + ($missing -join ', '))
}
# Android Studio's #GRADLE_LOCAL_JAVA_HOME resolves this ignored project-local
# property. Preserve unrelated properties instead of replacing the whole file.
$repoRoot = Split-Path -Parent $PSScriptRoot
$gradleDirectory = Join-Path $repoRoot '.gradle'
New-Item -ItemType Directory -Path $gradleDirectory -Force | Out-Null
$studioConfig = Join-Path $gradleDirectory 'config.properties'
$studioProperties = if (Test-Path -LiteralPath $studioConfig) { @(Get-Content -LiteralPath $studioConfig) } else { @() }
$javaProperty = 'java.home=' + (Resolve-Path -LiteralPath $jdk).Path.Replace('\', '/').Replace(':', '\:')
$foundJavaProperty = $false
$studioProperties = @($studioProperties | ForEach-Object {
    if ($_ -match '^\s*java\.home\s*[:=]') {
        if (!$foundJavaProperty) { $javaProperty }
        $foundJavaProperty = $true
    } else { $_ }
})
if (!$foundJavaProperty) { $studioProperties += $javaProperty }
[System.IO.File]::WriteAllText($studioConfig, ($studioProperties -join "`n") + "`n", (New-Object System.Text.UTF8Encoding $false))
Write-Output "Portable JDK verified at $jdk"
Write-Output "Android Studio project-local JDK configured in $studioConfig"
Write-Output "Build with: .\scripts\build.ps1 -JavaHome '$jdk' -SdkRoot '$SdkRoot' -Verify"
