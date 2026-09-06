#requires -Version 5.1
<#
.SYNOPSIS
Creates and uses the AirAngelVL upload signing key without writing secrets to Git.
.DESCRIPTION
Windows only. CreateKey refuses to overwrite any existing signing directory.
The PKCS12 keystore is password protected; its random password is encrypted with
Windows CurrentUser DPAPI. Both live in a private directory outside the repo.
Build signs locally and does not upload or publish anything to Google Play.
#>
[CmdletBinding()]
param(
    [ValidateSet('Status', 'CreateKey', 'VerifyKey', 'Build')]
    [string]$Command = 'Status',
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [switch]$Verify
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
if ($Verify -and $Command -ne 'Build') { throw '-Verify is supported only with -Command Build.' }
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'This signing helper requires Windows CurrentUser DPAPI.'
}
Add-Type -AssemblyName System.Security
$repoRoot = Split-Path -Parent $PSScriptRoot
$localRoot = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
$signingDirectory = Join-Path $localRoot 'Codex\google-play\airangelvl-signing'
$keyStorePath = Join-Path $signingDirectory 'upload-key.p12'
$passwordPath = Join-Path $signingDirectory 'password.dpapi'
$metadataPath = Join-Path $signingDirectory 'metadata.json'
$certificatePath = Join-Path $signingDirectory 'upload-certificate.pem'
$keyAlias = 'airangelvl-upload'
$entropy = [Text.Encoding]::UTF8.GetBytes('Codex.AirAngelVL.UploadSigning.v1')
$utf8 = New-Object Text.UTF8Encoding($false)

function Set-PrivateAcl([string]$Path, [bool]$Directory = $false) {
    $acl = if ($Directory) { New-Object Security.AccessControl.DirectorySecurity } else { New-Object Security.AccessControl.FileSecurity }
    $acl.SetAccessRuleProtection($true, $false)
    $ownerSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.SetOwner($ownerSid)
    foreach ($identity in @($ownerSid, (New-Object Security.Principal.SecurityIdentifier('S-1-5-18')))) {
        $rule = if ($Directory) {
            New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'ContainerInherit, ObjectInherit', 'None', 'Allow')
        } else {
            New-Object Security.AccessControl.FileSystemAccessRule($identity, 'FullControl', 'Allow')
        }
        $acl.AddAccessRule($rule)
    }
    # Avoid Set-Acl requesting unrelated audit/SACL privileges on this host.
    if ($PSVersionTable.PSVersion.Major -ge 7) {
        if ($Directory) { [IO.FileSystemAclExtensions]::SetAccessControl((New-Object IO.DirectoryInfo($Path)), $acl) }
        else { [IO.FileSystemAclExtensions]::SetAccessControl((New-Object IO.FileInfo($Path)), $acl) }
    } elseif ($Directory) { [IO.Directory]::SetAccessControl($Path, $acl) }
    else { [IO.File]::SetAccessControl($Path, $acl) }
}

function Assert-PrivateAcl([string]$Path) {
    $acl = Get-Acl -LiteralPath $Path
    $allowed = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18')
    if (!$acl.AreAccessRulesProtected) { throw 'Signing storage permissions must have inheritance disabled.' }
    foreach ($rule in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
        if ($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -notin $allowed) {
            throw 'Signing storage grants access beyond the current Windows account and SYSTEM.'
        }
    }
}

function Get-Keytool {
    if (!$script:JavaHome) {
        $portableJdk = 'C:\Coding\AAVL-toolchains\jdk-17.0.20.1+1'
        if (Test-Path -LiteralPath $portableJdk) { $script:JavaHome = $portableJdk }
    }
    if (!$script:JavaHome) { throw 'Provide -JavaHome pointing to JDK 17.' }
    $keytool = Join-Path $script:JavaHome 'bin\keytool.exe'
    if (!(Test-Path -LiteralPath $keytool -PathType Leaf)) { throw 'The selected JDK has no keytool.exe.' }
    return $keytool
}

function Invoke-Keytool([string[]]$Arguments) {
    $keytool = Get-Keytool
    # Native stderr is expected for keytool success messages. Capture it, but do
    # not echo raw output in case a future JDK includes sensitive input details.
    $previousErrorAction = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $captured = & $keytool @Arguments 2>&1
        $result = $LASTEXITCODE
    } finally { $ErrorActionPreference = $previousErrorAction }
    if ($result -ne 0) { throw "keytool failed (exit $result); no private credential output is shown." }
}

function Get-CertificateFingerprint([string]$Path) {
    $certificate = New-Object Security.Cryptography.X509Certificates.X509Certificate2($Path)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha256.ComputeHash($certificate.RawData))).Replace('-', ':') }
    finally { $sha256.Dispose(); $certificate.Dispose() }
}

function Read-SigningMetadata {
    foreach ($path in @($signingDirectory, $keyStorePath, $passwordPath, $metadataPath, $certificatePath)) {
        if (!(Test-Path -LiteralPath $path)) { throw 'Signing setup is absent or incomplete. Do not overwrite or recreate an existing upload key.' }
        Assert-PrivateAcl $path
    }
    try {
        $metadata = [IO.File]::ReadAllText($metadataPath) | ConvertFrom-Json
        if ($metadata.schemaVersion -ne 1 -or $metadata.packageName -cne 'com.airangelvl' -or
            $metadata.alias -cne $keyAlias -or $metadata.certificateSha256 -notmatch '^([0-9A-F]{2}:){31}[0-9A-F]{2}$') {
            throw 'Invalid metadata.'
        }
        if ((Get-CertificateFingerprint $certificatePath) -cne $metadata.certificateSha256) { throw 'Certificate differs.' }
        return $metadata
    } catch { throw 'The signing metadata or public certificate is invalid. Restore the existing signing setup instead of creating another key.' }
}

function Read-SigningPassword {
    $plain = $null
    try {
        $plain = [Security.Cryptography.ProtectedData]::Unprotect([IO.File]::ReadAllBytes($passwordPath), $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        return [Text.Encoding]::UTF8.GetString($plain)
    } catch { throw 'Cannot decrypt the upload-key password. Use the Windows account that created this setup.' }
    finally { if ($plain) { [Array]::Clear($plain, 0, $plain.Length) } }
}

function Assert-KeyMatchesMetadata($Metadata) {
    $temporaryCertificate = Join-Path $signingDirectory ('verify-' + [Guid]::NewGuid().ToString('N') + '.cer')
    try {
        Invoke-Keytool @('-exportcert', '-keystore', $keyStorePath, '-storetype', 'PKCS12', '-alias', $keyAlias,
            '-storepass:env', 'AAVL_UPLOAD_STORE_PASSWORD', '-file', $temporaryCertificate)
        Set-PrivateAcl $temporaryCertificate
        if ((Get-CertificateFingerprint $temporaryCertificate) -cne $Metadata.certificateSha256) {
            throw 'The keystore certificate does not match the recorded upload key. Signing was stopped.'
        }
    } finally {
        if (Test-Path -LiteralPath $temporaryCertificate -PathType Leaf) { Remove-Item -LiteralPath $temporaryCertificate -Force }
    }
}

$environmentNames = @('AAVL_UPLOAD_STORE_FILE', 'AAVL_UPLOAD_STORE_PASSWORD', 'AAVL_UPLOAD_KEY_ALIAS', 'AAVL_UPLOAD_KEY_PASSWORD')
$previousEnvironment = @{}
foreach ($name in $environmentNames) { $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$accountSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$mutex = New-Object Threading.Mutex($false, "Local\Codex.AirAngelVL.UploadSigning.$accountSid")
$locked = $false
try {
    try { $locked = $mutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $locked = $true }
    if (!$locked) { throw 'Another signing operation is active. Retry after it completes.' }
    if ($Command -eq 'CreateKey') {
        if (Test-Path -LiteralPath $signingDirectory) { throw 'Signing storage already exists. Refusing to replace or rotate the upload key.' }
        $null = Get-Keytool
        New-Item -ItemType Directory -Path $signingDirectory | Out-Null
        Set-PrivateAcl $signingDirectory $true
        Assert-PrivateAcl $signingDirectory
        $random = New-Object byte[] 48
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($random); $password = [Convert]::ToBase64String($random) }
        finally { $rng.Dispose(); [Array]::Clear($random, 0, $random.Length) }
        $plain = [Text.Encoding]::UTF8.GetBytes($password)
        try {
            $encrypted = [Security.Cryptography.ProtectedData]::Protect($plain, $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
            [IO.File]::WriteAllBytes($passwordPath, $encrypted)
            Set-PrivateAcl $passwordPath
        } finally { [Array]::Clear($plain, 0, $plain.Length) }
        $env:AAVL_UPLOAD_STORE_PASSWORD = $password
        $password = $null
        Invoke-Keytool @('-genkeypair', '-keystore', $keyStorePath, '-storetype', 'PKCS12', '-alias', $keyAlias,
            '-keyalg', 'RSA', '-keysize', '3072', '-sigalg', 'SHA256withRSA', '-validity', '10000',
            '-dname', 'CN=AirAngelVL Upload', '-storepass:env', 'AAVL_UPLOAD_STORE_PASSWORD',
            '-keypass:env', 'AAVL_UPLOAD_STORE_PASSWORD', '-noprompt')
        Set-PrivateAcl $keyStorePath
        Invoke-Keytool @('-exportcert', '-rfc', '-keystore', $keyStorePath, '-storetype', 'PKCS12', '-alias', $keyAlias,
            '-storepass:env', 'AAVL_UPLOAD_STORE_PASSWORD', '-file', $certificatePath)
        Set-PrivateAcl $certificatePath
        $metadata = [ordered]@{
            schemaVersion = 1; packageName = 'com.airangelvl'; alias = $keyAlias
            algorithm = 'RSA'; keySize = 3072; createdUtc = [DateTime]::UtcNow.ToString('o')
            certificateSha256 = Get-CertificateFingerprint $certificatePath
            credentialProtection = 'Windows CurrentUser DPAPI'; intendedUse = 'Google Play upload key'
        }
        [IO.File]::WriteAllText($metadataPath, ($metadata | ConvertTo-Json), $utf8)
        Set-PrivateAcl $metadataPath
        $metadata = Read-SigningMetadata
        if ((Read-SigningPassword) -cne $env:AAVL_UPLOAD_STORE_PASSWORD) { throw 'Signing password read-back verification failed.' }
        Assert-KeyMatchesMetadata $metadata
        [pscustomobject]@{ Status = 'Created and verified'; Directory = $signingDirectory; Package = $metadata.packageName; CertificateSHA256 = $metadata.certificateSha256 }
    } elseif ($Command -eq 'Status') {
        if (!(Test-Path -LiteralPath $signingDirectory)) {
            [pscustomobject]@{ Status = 'Not configured'; Directory = $signingDirectory; Package = 'com.airangelvl' }
        } else {
            $metadata = Read-SigningMetadata
            [pscustomobject]@{ Status = 'Configured; use VerifyKey to test decryption'; Directory = $signingDirectory; Package = $metadata.packageName; CertificateSHA256 = $metadata.certificateSha256 }
        }
    } else {
        $metadata = Read-SigningMetadata
        $env:AAVL_UPLOAD_STORE_PASSWORD = Read-SigningPassword
        $env:AAVL_UPLOAD_KEY_PASSWORD = $env:AAVL_UPLOAD_STORE_PASSWORD
        $env:AAVL_UPLOAD_STORE_FILE = $keyStorePath
        $env:AAVL_UPLOAD_KEY_ALIAS = $keyAlias
        Assert-KeyMatchesMetadata $metadata
        if ($Command -eq 'VerifyKey') {
            [pscustomobject]@{ Status = 'Keystore, password, certificate and ACL verified'; Package = $metadata.packageName; CertificateSHA256 = $metadata.certificateSha256 }
        } else {
            $buildParameters = @{ JavaHome = $JavaHome; Tasks = @(':app:bundleUsbOnlyRelease', ':app:assembleUsbOnlyRelease') }
            if ($SdkRoot) { $buildParameters.SdkRoot = $SdkRoot }
            if ($Verify) { $buildParameters.Verify = $true }
            & (Join-Path $repoRoot 'scripts\build.ps1') @buildParameters
            [pscustomobject]@{ Status = 'Signed release build completed locally; nothing published'; Package = $metadata.packageName; CertificateSHA256 = $metadata.certificateSha256 }
        }
    }
} finally {
    foreach ($name in $environmentNames) { [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], 'Process') }
    if ($locked) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
