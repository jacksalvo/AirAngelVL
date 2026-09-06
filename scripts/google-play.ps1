#requires -Version 5.1
<#
.SYNOPSIS
Stores a Google Play service-account credential locally and verifies API access.
.DESCRIPTION
Windows only. Credentials are encrypted with Windows CurrentUser DPAPI outside
the repository. Tokens exist only in memory and are never returned by commands.
VerifyAccess and ListTracks create then delete an uncommitted edit. Do not run
them alongside a publishing job using the same service account: Google allows
only one open edit per user. This helper cannot publish an app or commit edits.
.EXAMPLE
.\scripts\google-play.ps1 -Command ImportCredential -ServiceAccountJson C:\secure-download\key.json
.EXAMPLE
.\scripts\google-play.ps1 -Command TestAuthentication
.EXAMPLE
.\scripts\google-play.ps1 -Command ListTracks
#>
[CmdletBinding()]
param(
    [ValidateSet('Status', 'ImportCredential', 'TestAuthentication', 'VerifyAccess', 'ListTracks', 'SelfTest')]
    [string]$Command = 'Status',
    [ValidatePattern('^[a-z][a-z0-9-]{0,39}$')]
    [string]$Profile = 'airangelvl',
    [string]$ServiceAccountJson,
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$')]
    [string]$PackageName = 'com.airangelvl',
    [ValidatePattern('^[0-9]{10,30}$')]
    [string]$DeveloperId,
    [switch]$ReplaceCredential
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'This helper uses Windows CurrentUser DPAPI and must run on Windows.'
}
Add-Type -AssemblyName System.Security
Add-Type -AssemblyName System.Net.Http
$localRoot = [Environment]::GetFolderPath([Environment+SpecialFolder]::LocalApplicationData)
$configDirectory = Join-Path $localRoot 'Codex\google-play'
$credentialDirectory = Join-Path $localRoot 'Codex\credentials'
$configPath = Join-Path $configDirectory "$Profile.json"
$credentialPath = Join-Path $credentialDirectory "$Profile-google-play.dpapi"
$tokenEndpoint = 'https://oauth2.googleapis.com/token'
$publisherScope = 'https://www.googleapis.com/auth/androidpublisher'
$entropy = [Text.Encoding]::UTF8.GetBytes('Codex.GooglePlay.ServiceAccount.v1')
$utf8 = New-Object Text.UTF8Encoding($false)

function ConvertTo-Base64Url([byte[]]$Bytes) {
    [Convert]::ToBase64String($Bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Open-ServiceAccountRsa([string]$Pem) {
    $key = $null
    $der = $null
    try {
        if ($Pem -notmatch '(?s)^\s*-----BEGIN PRIVATE KEY-----\s*([A-Za-z0-9+/=\s]+)\s*-----END PRIVATE KEY-----\s*$') {
            throw 'Invalid PKCS8 format.'
        }
        $der = [Convert]::FromBase64String(($Matches[1] -replace '\s', ''))
        $key = [Security.Cryptography.CngKey]::Import($der, [Security.Cryptography.CngKeyBlobFormat]::Pkcs8PrivateBlob)
        $rsa = New-Object Security.Cryptography.RSACng($key)
        if ($rsa.KeySize -lt 2048) { $rsa.Dispose(); throw 'RSA key is too small.' }
        return $rsa
    } catch {
        throw 'The credential must contain a valid Google-issued PKCS8 RSA private key of at least 2048 bits.'
    } finally {
        if ($key) { $key.Dispose() }
        if ($der) { [Array]::Clear($der, 0, $der.Length) }
    }
}

function ConvertFrom-ServiceAccountJson([string]$Json) {
    try {
        $account = $Json | ConvertFrom-Json
        if ($account.type -ne 'service_account' -or
            $account.token_uri -ne $tokenEndpoint -or
            $account.client_email -notmatch '^[a-zA-Z0-9._-]+@[a-zA-Z0-9-]+\.iam\.gserviceaccount\.com$' -or
            $account.project_id -notmatch '^[a-z][a-z0-9-]{4,62}[a-z0-9]$' -or
            $account.private_key_id -notmatch '^[a-fA-F0-9]{16,128}$' -or
            [string]::IsNullOrWhiteSpace($account.private_key)) {
            throw 'Invalid credential fields.'
        }
    } catch { throw 'The file is not a supported Google service-account JSON credential.' }
    $rsa = Open-ServiceAccountRsa $account.private_key
    $rsa.Dispose()
    return $account
}

function Set-LocalFileAcl([string]$Path, [Security.AccessControl.FileSecurity]$Acl) {
    # Apply only the owner and DACL. Set-Acl may request SACL privileges when
    # reapplying permissions to an already protected file on this Windows host.
    $sections = [Security.AccessControl.AccessControlSections]::Owner -bor [Security.AccessControl.AccessControlSections]::Access
    $permissions = New-Object Security.AccessControl.FileSecurity
    $permissions.SetSecurityDescriptorSddlForm($Acl.GetSecurityDescriptorSddlForm($sections), $sections)
    if ($PSVersionTable.PSVersion.Major -ge 7) {
        [IO.FileSystemAclExtensions]::SetAccessControl((New-Object IO.FileInfo($Path)), $permissions)
    } else { [IO.File]::SetAccessControl($Path, $permissions) }
}

function Set-PrivateFileAcl([string]$Path) {
    $acl = New-Object Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl.SetOwner($sid)
    $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'Allow')))
    $systemSid = New-Object Security.Principal.SecurityIdentifier('S-1-5-18')
    $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($systemSid, 'FullControl', 'Allow')))
    Set-LocalFileAcl $Path $acl
}

function Read-LocalConfig([string]$Path = $configPath) {
    if (!(Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw 'No local Google Play profile is configured. Run ImportCredential first.'
    }
    try {
        $config = [IO.File]::ReadAllText($Path) | ConvertFrom-Json
        if ($config.version -ne 1 -or
            $config.packageName -notmatch '^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$' -or
            $config.serviceAccountEmail -notmatch '^[a-zA-Z0-9._-]+@[a-zA-Z0-9-]+\.iam\.gserviceaccount\.com$' -or
            $config.projectId -notmatch '^[a-z][a-z0-9-]{4,62}[a-z0-9]$') {
            throw 'Invalid configuration.'
        }
    } catch { throw 'The local Google Play configuration is invalid. Import the credential again.' }
    return $config
}

function Read-LocalServiceAccount($Config, [string]$Path = $credentialPath) {
    $plain = $null
    try {
        $encrypted = [IO.File]::ReadAllBytes($Path)
        $plain = [Security.Cryptography.ProtectedData]::Unprotect($encrypted, $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        $account = ConvertFrom-ServiceAccountJson ([Text.Encoding]::UTF8.GetString($plain))
        if ($account.client_email -ne $Config.serviceAccountEmail -or $account.project_id -ne $Config.projectId) {
            throw 'Configuration and credential do not match.'
        }
        return $account
    } catch {
        throw 'Cannot open the local credential. Use the Windows account that imported it, or import it again on this machine.'
    } finally {
        if ($plain) { [Array]::Clear($plain, 0, $plain.Length) }
    }
}

function Install-StagedLocalFile([string]$StagePath, [string]$Destination, [bool]$Replacing) {
    if ($Replacing) { [IO.File]::Replace($StagePath, $Destination, [NullString]::Value) }
    else { [IO.File]::Move($StagePath, $Destination) }
    # File.Replace can retain the destination ACL. Apply the private ACL again
    # before the pair is considered active, including when replacing old files.
    Set-PrivateFileAcl $Destination
}

function Save-LocalCredentialPair([byte[]]$Encrypted, [string]$MetadataJson, [bool]$AllowReplacement) {
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    $mutex = New-Object Threading.Mutex($false, "Local\Codex.GooglePlay.Import.$sid.$Profile")
    $locked = $false
    $committed = $false
    $rollbackComplete = $true
    $files = @()
    try {
        try { $locked = $mutex.WaitOne(0) }
        catch [Threading.AbandonedMutexException] { $locked = $true }
        if (!$locked) { throw 'Another credential import is using this profile. Retry when it finishes.' }
        if (!$AllowReplacement -and ((Test-Path -LiteralPath $credentialPath) -or (Test-Path -LiteralPath $configPath))) {
            throw 'This profile already exists. Use -ReplaceCredential only when intentionally replacing its credential.'
        }
        New-Item -ItemType Directory -Path $credentialDirectory -Force | Out-Null
        New-Item -ItemType Directory -Path $configDirectory -Force | Out-Null
        $transactionId = [Guid]::NewGuid().ToString('N')
        foreach ($destination in @($credentialPath, $configPath)) {
            if ((Test-Path -LiteralPath $destination) -and !(Test-Path -LiteralPath $destination -PathType Leaf)) {
                throw 'A profile destination is not a file. No credential files were changed.'
            }
            $exists = Test-Path -LiteralPath $destination -PathType Leaf
            $oldAcl = if ($exists) { Get-Acl -LiteralPath $destination } else { $null }
            $files += [pscustomobject]@{
                Destination = $destination; Stage = "$destination.$transactionId.tmp";
                Backup = "$destination.$transactionId.bak"; Existed = $exists;
                OriginalAcl = $oldAcl; Attempted = $false
            }
        }
        [IO.File]::WriteAllBytes($files[0].Stage, $Encrypted)
        Set-PrivateFileAcl $files[0].Stage
        [IO.File]::WriteAllText($files[1].Stage, $MetadataJson, $utf8)
        Set-PrivateFileAcl $files[1].Stage
        # Read, parse, and decrypt the staged pair before touching the active
        # files. This also checks that the account and metadata agree.
        $stagedConfig = Read-LocalConfig $files[1].Stage
        $stagedAccount = Read-LocalServiceAccount $stagedConfig $files[0].Stage
        $stagedAccount = $null
        if ([IO.File]::ReadAllText($files[1].Stage) -cne $MetadataJson) {
            throw 'Staged metadata did not pass read-back verification.'
        }
        foreach ($file in $files) {
            if (!(Get-Acl -LiteralPath $file.Stage).AreAccessRulesProtected) {
                throw 'Staged file permissions did not pass verification.'
            }
            if ($file.Existed) {
                [IO.File]::Copy($file.Destination, $file.Backup, $false)
                Set-PrivateFileAcl $file.Backup
            }
        }
        foreach ($file in $files) {
            # Mark the attempt first: an install can fail after replacing bytes
            # but before its ACL is applied, and that still needs rollback.
            $file.Attempted = $true
            Install-StagedLocalFile $file.Stage $file.Destination $file.Existed
        }
        $activeConfig = Read-LocalConfig
        $activeAccount = Read-LocalServiceAccount $activeConfig
        $activeAccount = $null
        $committed = $true
    } finally {
        if (!$committed) {
            for ($index = $files.Count - 1; $index -ge 0; $index--) {
                $file = $files[$index]
                if (!$file.Attempted) { continue }
                try {
                    if ($file.Existed) {
                        # Keep the backup until both restorations succeed so a
                        # filesystem failure cannot consume the recovery copy.
                        [IO.File]::Copy($file.Backup, $file.Destination, $true)
                        Set-LocalFileAcl $file.Destination $file.OriginalAcl
                    } elseif (Test-Path -LiteralPath $file.Destination -PathType Leaf) {
                        [IO.File]::Delete($file.Destination)
                    }
                } catch { $rollbackComplete = $false }
            }
        }
        foreach ($file in $files) {
            foreach ($temporaryPath in @($file.Stage, $file.Backup)) {
                if ($temporaryPath -eq $file.Backup -and !$rollbackComplete) { continue }
                try { if ([IO.File]::Exists($temporaryPath)) { [IO.File]::Delete($temporaryPath) } }
                catch { Write-Warning 'A protected temporary credential file could not be removed from the local profile directory.' }
            }
        }
        if ($locked) { $mutex.ReleaseMutex() }
        $mutex.Dispose()
        if (!$rollbackComplete) {
            throw 'Credential activation failed and rollback was incomplete. Protected .bak recovery files were preserved next to the profile files; restore them before using this profile.'
        }
    }
}

function New-ServiceAccountAssertion($Account) {
    $rsa = Open-ServiceAccountRsa $Account.private_key
    try {
        $now = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        $header = @{ alg = 'RS256'; typ = 'JWT'; kid = $Account.private_key_id } | ConvertTo-Json -Compress
        $claims = @{ iss = $Account.client_email; scope = $publisherScope; aud = $tokenEndpoint; iat = $now; exp = $now + 600 } | ConvertTo-Json -Compress
        $unsigned = (ConvertTo-Base64Url $utf8.GetBytes($header)) + '.' + (ConvertTo-Base64Url $utf8.GetBytes($claims))
        $signature = $rsa.SignData($utf8.GetBytes($unsigned), [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)
        return $unsigned + '.' + (ConvertTo-Base64Url $signature)
    } finally { $rsa.Dispose() }
}

function Invoke-GoogleJson([string]$Method, [string]$Uri, [string]$Token, [string]$Body, [string]$ContentType = 'application/json') {
    # Fixed Google endpoints only; redirects are disabled so credentials cannot
    # be forwarded to a destination supplied by an HTTP redirect.
    if ($Uri -ne $tokenEndpoint -and $Uri -notmatch '^https://androidpublisher\.googleapis\.com/androidpublisher/v3/applications/[A-Za-z0-9._]+/edits(?:/[A-Za-z0-9_-]+(?:/tracks)?)?$') {
        throw 'Unsupported Google API endpoint.'
    }
    $handler = New-Object Net.Http.HttpClientHandler
    $handler.AllowAutoRedirect = $false
    $client = New-Object Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(45)
    $request = New-Object Net.Http.HttpRequestMessage((New-Object Net.Http.HttpMethod($Method)), $Uri)
    $response = $null
    try {
        if ($Token) { $request.Headers.Authorization = New-Object Net.Http.Headers.AuthenticationHeaderValue('Bearer', $Token) }
        if ($null -ne $Body -and $Body.Length -gt 0) { $request.Content = New-Object Net.Http.StringContent($Body, $utf8, $ContentType) }
        try { $response = $client.SendAsync($request).GetAwaiter().GetResult() }
        catch { throw 'Google API request failed before receiving a response. Check network access and retry.' }
        if (!$response.IsSuccessStatusCode) {
            $status = [int]$response.StatusCode
            $hint = switch ($status) {
                400 { 'Check the credential, computer clock, and app setup.' }
                401 { 'Check whether the service-account key is still active.' }
                403 { 'Check API enablement and the service account permissions in Google Play Console.' }
                404 { 'Check the package name and whether its first build has been uploaded in Play Console.' }
                409 { 'Another edit or Console operation may be in progress.' }
                429 { 'The API rate limit was reached. Retry later.' }
                default { 'Retry later or check the Google API status.' }
            }
            throw "Google API returned HTTP $status. $hint Remote response bodies are not logged."
        }
        try {
            $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            if (![string]::IsNullOrWhiteSpace($text)) { return ($text | ConvertFrom-Json) }
        } catch { throw 'Google API returned an unreadable response. Remote response bodies are not logged.' }
    } finally {
        if ($response) { $response.Dispose() }
        $request.Dispose()
        $client.Dispose()
        $handler.Dispose()
    }
}

function Get-AccessToken($Account) {
    $assertion = New-ServiceAccountAssertion $Account
    $body = 'grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=' + [Uri]::EscapeDataString($assertion)
    try {
        $result = Invoke-GoogleJson 'POST' $tokenEndpoint '' $body 'application/x-www-form-urlencoded'
        if (!$result -or !$result.access_token -or $result.token_type -ne 'Bearer') {
            throw 'Google did not return the expected access token.'
        }
        return [string]$result.access_token
    } finally { $assertion = $null; $body = $null; $result = $null }
}

function Invoke-OfflineSelfTest {
    $rsa = New-Object Security.Cryptography.RSACng(2048)
    $der = $null
    $plain = $null
    $roundtrip = $null
    try {
        $der = $rsa.Key.Export([Security.Cryptography.CngKeyBlobFormat]::Pkcs8PrivateBlob)
        $dummy = [pscustomobject]@{
            type = 'service_account'; project_id = 'codex-offline-test';
            client_email = 'offline-test@codex-offline-test.iam.gserviceaccount.com';
            private_key_id = ('a' * 40); token_uri = $tokenEndpoint;
            private_key = "-----BEGIN PRIVATE KEY-----`n$([Convert]::ToBase64String($der))`n-----END PRIVATE KEY-----`n"
        }
        $validated = ConvertFrom-ServiceAccountJson ($dummy | ConvertTo-Json -Compress)
        $jwt = New-ServiceAccountAssertion $validated
        $parts = $jwt.Split('.')
        $encodedSignature = $parts[2].Replace('-', '+').Replace('_', '/')
        $encodedSignature += '=' * ((4 - $encodedSignature.Length % 4) % 4)
        $signature = [Convert]::FromBase64String($encodedSignature)
        $unsigned = $utf8.GetBytes($parts[0] + '.' + $parts[1])
        if (!$rsa.VerifyData($unsigned, $signature, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)) {
            throw 'Offline JWT signature verification failed.'
        }
        $unsigned[0] = $unsigned[0] -bxor 1
        if ($rsa.VerifyData($unsigned, $signature, [Security.Cryptography.HashAlgorithmName]::SHA256, [Security.Cryptography.RSASignaturePadding]::Pkcs1)) {
            throw 'Offline JWT tamper detection failed.'
        }
        $plain = $utf8.GetBytes('offline DPAPI roundtrip only')
        $encrypted = [Security.Cryptography.ProtectedData]::Protect($plain, $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        $roundtrip = [Security.Cryptography.ProtectedData]::Unprotect($encrypted, $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        if ([Convert]::ToBase64String($plain) -ne [Convert]::ToBase64String($roundtrip)) { throw 'Offline DPAPI roundtrip failed.' }
        foreach ($invalid in @('{}', '{invalid-json', '{"type":"authorized_user"}')) {
            $rejected = $false
            try { $null = ConvertFrom-ServiceAccountJson $invalid } catch { $rejected = $true }
            if (!$rejected) { throw 'Invalid credentials were accepted.' }
        }
        $rejectedPem = $false
        try { $unexpected = Open-ServiceAccountRsa 'not a private key'; $unexpected.Dispose() } catch { $rejectedPem = $true }
        if (!$rejectedPem) { throw 'Invalid private key was accepted.' }
        [pscustomobject]@{ Result = 'Passed'; Checks = 'PKCS8 import, RS256 signing/verification, tamper rejection, CurrentUser DPAPI roundtrip, invalid JSON and key rejection'; NetworkRequests = 0; FilesWritten = 0 }
    } finally {
        $rsa.Dispose()
        foreach ($buffer in @($der, $plain, $roundtrip)) { if ($buffer) { [Array]::Clear($buffer, 0, $buffer.Length) } }
    }
}

if ($Command -eq 'SelfTest') { Invoke-OfflineSelfTest; return }
if ($Command -eq 'ImportCredential') {
    if (!$ServiceAccountJson) { throw 'Provide -ServiceAccountJson with the local path to the downloaded key JSON.' }
    $plain = $null
    try {
        try { $json = [IO.File]::ReadAllText((Resolve-Path -LiteralPath $ServiceAccountJson).ProviderPath) }
        catch { throw 'Cannot read the specified service-account file.' }
        $account = ConvertFrom-ServiceAccountJson $json
        $plain = $utf8.GetBytes($json)
        $encrypted = [Security.Cryptography.ProtectedData]::Protect($plain, $entropy, [Security.Cryptography.DataProtectionScope]::CurrentUser)
        $metadata = [ordered]@{ version = 1; projectId = $account.project_id; serviceAccountEmail = $account.client_email; packageName = $PackageName }
        if ($DeveloperId) { $metadata.developerId = $DeveloperId }
        Save-LocalCredentialPair $encrypted ($metadata | ConvertTo-Json) $ReplaceCredential.IsPresent
        [pscustomobject]@{ Result = 'Imported'; Profile = $Profile; PackageName = $PackageName; ServiceAccountEmail = $account.client_email; ConfigPath = $configPath; CredentialPath = $credentialPath; SourceFileDeleted = $false }
    } finally {
        if ($plain) { [Array]::Clear($plain, 0, $plain.Length) }
        $json = $null; $account = $null
    }
    return
}
if ($Command -eq 'Status' -and !(Test-Path -LiteralPath $configPath)) {
    [pscustomobject]@{ Configured = $false; Profile = $Profile; ConfigPath = $configPath; CredentialPath = $credentialPath }
    return
}
$config = Read-LocalConfig
if ($Command -eq 'Status') {
    $configuredDeveloperId = if ($config.PSObject.Properties['developerId']) { $config.developerId } else { $null }
    [pscustomobject]@{ Configured = (Test-Path -LiteralPath $credentialPath -PathType Leaf); Profile = $Profile; ProjectId = $config.projectId; ServiceAccountEmail = $config.serviceAccountEmail; PackageName = $config.packageName; DeveloperId = $configuredDeveloperId; ConfigPath = $configPath; CredentialPath = $credentialPath }
    return
}
$account = $null
$token = $null
$editId = $null
try {
    $account = Read-LocalServiceAccount $config
    $token = Get-AccessToken $account
    if ($Command -eq 'TestAuthentication') {
        [pscustomobject]@{ Authenticated = $true; ServiceAccountEmail = $config.serviceAccountEmail; Scope = $publisherScope; AppPermissionsVerified = $false }
        return
    }
    $effectivePackage = if ($PSBoundParameters.ContainsKey('PackageName')) { $PackageName } else { $config.packageName }
    $baseUri = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$effectivePackage/edits"
    $edit = Invoke-GoogleJson 'POST' $baseUri $token '{}'
    if (!$edit -or $edit.id -notmatch '^[A-Za-z0-9_-]+$') { throw 'Google did not return a valid temporary edit ID.' }
    $editId = $edit.id
    $tracks = Invoke-GoogleJson 'GET' "$baseUri/$editId/tracks" $token ''
    # Delete before returning success, so a failed cleanup is not reported as a
    # successful complete verification. The finally block retries cleanup once.
    $null = Invoke-GoogleJson 'DELETE' "$baseUri/$editId" $token ''
    $editId = $null
    if ($Command -eq 'ListTracks') {
        $trackItems = if ($tracks -and $tracks.PSObject.Properties['tracks']) { @($tracks.tracks) } else { @() }
        [pscustomobject]@{ PackageName = $effectivePackage; Tracks = $trackItems; TemporaryEditDeleted = $true; Published = $false }
    } else {
        [pscustomobject]@{ Authenticated = $true; AppAccessVerified = $true; PackageName = $effectivePackage; TemporaryEditDeleted = $true; Published = $false }
    }
} finally {
    if ($editId -and $token) {
        try { $null = Invoke-GoogleJson 'DELETE' "$baseUri/$editId" $token '' }
        catch { Write-Warning 'The temporary edit could not be deleted. No changes were committed; it will expire. Avoid concurrent publishing until the edit is resolved.' }
    }
    $account = $null; $token = $null
}
