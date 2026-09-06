#requires -Version 5.1
<#
.SYNOPSIS
Stages and verifies AirAngelVL Google Play edits without publishing a release.
.DESCRIPTION
Read GOOGLE_PLAY_RELEASE.md first. Commands are explicit; there is no default
mutation. Stage creates only a production draft. CommitDraft retains draft
status and requests changesNotSentForReview=true. Submission is a separate
Console action requiring current authorization. Never run another edits-based
helper or change the app in Console while an edit is open.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][ValidateSet('SelfTest','CheckFiles','ReadSnapshot','Stage','Validate','CommitDraft')]
    [string]$Command,
    [ValidateSet('codex-publisher')][string]$Profile = 'codex-publisher',
    [ValidateSet('com.airangelvl')][string]$PackageName = 'com.airangelvl',
    [string]$Bundle,
    [ValidatePattern('^[a-fA-F0-9]{64}$')][string]$BundleSha256,
    [ValidateRange(1,2100000000)][int]$VersionCode = 3,
    [string]$ReleaseName = '1.0.0',
    [string]$ListingDirectory,
    [string]$Icon,
    [string]$FeatureGraphic,
    [string[]]$Screenshots,
    [ValidatePattern('^[A-Za-z0-9_-]{1,100}$')][string]$EditId,
    [switch]$NewEdit,
    [switch]$ConfirmNoOtherEdit,
    [switch]$ReplaceExistingMedia
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0
$releaseCommand = $Command
$releaseProfile = $Profile
$releasePackage = $PackageName
$releaseEditArgument = $EditId
$releaseRoot = Split-Path -Parent $PSScriptRoot
$releaseUtf8 = New-Object Text.UTF8Encoding($false)
$releaseLocalRoot = Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Codex\google-play\release-edits'
$releaseStatePath = Join-Path $releaseLocalRoot "$releaseProfile-$releasePackage.json"
$releaseBase = "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$releasePackage/edits"
$releaseToken = $null
$releaseState = $null
if (!$ListingDirectory) { $ListingDirectory = Join-Path $releaseRoot 'store\en-US' }
if (!$Icon) { $Icon = Join-Path $releaseRoot 'store\graphics\icon-512.png' }
if (!$FeatureGraphic) { $FeatureGraphic = Join-Path $releaseRoot 'store\graphics\feature-graphic-1024x500.png' }
Add-Type -AssemblyName System.Net.Http
Add-Type -AssemblyName System.Drawing

function Get-ReleaseArray($Object, [string]$Property) {
    if ($null -ne $Object -and $Object.PSObject.Properties[$Property]) { @($Object.$Property) }
}
function Assert-ReleaseEndpoint([string]$Method, [string]$Uri) {
    $escapedBase = [regex]::Escape($releaseBase)
    $id = '[A-Za-z0-9_-]{1,100}'
    $images = 'listings/en-US/(icon|featureGraphic|phoneScreenshots)'
    $allowed = switch ($Method) {
        'GET' { $Uri -match "^$escapedBase/$id(?:/(?:bundles|tracks|listings|listings/en-US|$images))?$" }
        'PUT' { $Uri -match "^$escapedBase/$id/(?:tracks/production|listings/en-US)$" }
        'DELETE' { $Uri -match "^$escapedBase/$id(?:/$images)?$" }
        'POST' {
            ($Uri -eq $releaseBase) -or
            ($Uri -match "^$escapedBase/${id}:validate$") -or
            ($Uri -match "^$escapedBase/${id}:commit\?changesNotSentForReview=true&changesInReviewBehavior=ERROR_IF_IN_REVIEW$") -or
            ($Uri -match "^https://androidpublisher\.googleapis\.com/upload/androidpublisher/v3/applications/$releasePackage/edits/$id/(?:bundles|$images)\?uploadType=media$")
        }
        default { $false }
    }
    if (!$allowed) { throw 'Unsupported release endpoint or HTTP method.' }
}
function Get-ReleaseError([string]$Body, [int]$Status) {
    # Return only a bounded message field. Never log response headers, request
    # objects, access tokens, or the full response. Suppress credential-shaped
    # error bodies rather than attempting to redact only parts of a secret.
    $message = ''
    try {
        $parsed = $Body | ConvertFrom-Json
        if ($parsed.error -and $parsed.error.message) { $message = [string]$parsed.error.message }
    } catch { }
    if ($message -match '(?i)bearer|access.token|private.key|ya29\.|eyJ[A-Za-z0-9_-]{20}|-----BEGIN') { $message = '[message suppressed]' }
    $message = ($message -replace '[\x00-\x1f\x7f]', ' ')
    if ($message.Length -gt 500) { $message = $message.Substring(0,500) }
    return "Google Play API HTTP $Status. $message"
}
function Invoke-ReleaseApi([string]$Method, [string]$Uri, $Body = $null, $File = $null) {
    Assert-ReleaseEndpoint $Method $Uri
    $handler = New-Object Net.Http.HttpClientHandler
    $handler.AllowAutoRedirect = $false
    $client = New-Object Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(180)
    $request = New-Object Net.Http.HttpRequestMessage((New-Object Net.Http.HttpMethod($Method)), $Uri)
    $response = $null
    $stream = $null
    try {
        $request.Headers.Authorization = New-Object Net.Http.Headers.AuthenticationHeaderValue('Bearer',$releaseToken)
        if ($null -ne $File) {
            # Hash and send the same locked stream; another process cannot
            # replace the reviewed payload between verification and upload.
            $stream = [IO.File]::Open($File.path,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
            $hasher = [Security.Cryptography.SHA256]::Create()
            try { $hash = ([BitConverter]::ToString($hasher.ComputeHash($stream))).Replace('-','').ToLowerInvariant() }
            finally { $hasher.Dispose() }
            if ($hash -ne $File.sha256 -or $stream.Length -ne $File.length) { throw 'A reviewed upload file changed. No upload was sent.' }
            $stream.Position = 0
            $request.Content = New-Object Net.Http.StreamContent($stream)
            $request.Content.Headers.ContentType = New-Object Net.Http.Headers.MediaTypeHeaderValue($File.mime)
        } elseif ($null -ne $Body) {
            $request.Content = New-Object Net.Http.StringContent(($Body | ConvertTo-Json -Depth 20 -Compress),$releaseUtf8,'application/json')
        }
        try { $response = $client.SendAsync($request).GetAwaiter().GetResult() }
        catch { throw 'Google Play request did not receive a complete response. Its outcome may be uncertain; inspect the saved edit before retrying.' }
        $responseText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (!$response.IsSuccessStatusCode) { throw (Get-ReleaseError $responseText ([int]$response.StatusCode)) }
        if (![string]::IsNullOrWhiteSpace($responseText)) {
            try { return ($responseText | ConvertFrom-Json) }
            catch { throw 'Google Play returned unreadable JSON. Response bodies are not logged.' }
        }
    } finally {
        if ($response) { $response.Dispose() }
        $request.Dispose()
        if ($stream) { $stream.Dispose() }
        $client.Dispose()
        $handler.Dispose()
    }
}
function Get-ReleaseFile([string]$Path, [string]$Kind) {
    if ([string]::IsNullOrWhiteSpace($Path) -or !(Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing $Kind file." }
    $resolved = (Resolve-Path -LiteralPath $Path).ProviderPath
    $item = Get-Item -LiteralPath $resolved
    if ($item.Length -eq 0) { throw "Empty $Kind file." }
    $mime = 'text/plain'
    if ($Kind -eq 'bundle') {
        if ($item.Extension -ne '.aab') { throw 'The bundle must be an .aab file.' }
        $mime = 'application/octet-stream'
    } elseif ($Kind -in @('icon','featureGraphic','phoneScreenshots')) {
        if ($item.Extension -notin @('.png','.jpg','.jpeg')) { throw 'Store images must be PNG or JPEG.' }
        $mime = if ($item.Extension -eq '.png') { 'image/png' } else { 'image/jpeg' }
        $bitmap = [Drawing.Image]::FromFile($resolved)
        try {
            $w = $bitmap.Width; $h = $bitmap.Height
            if ($Kind -eq 'icon' -and ($w -ne 512 -or $h -ne 512)) { throw 'Icon must be 512 x 512.' }
            if ($Kind -eq 'featureGraphic' -and ($w -ne 1024 -or $h -ne 500)) { throw 'Feature graphic must be 1024 x 500.' }
            if ($Kind -eq 'phoneScreenshots' -and ([Math]::Min($w,$h) -lt 320 -or [Math]::Max($w,$h) -gt 3840 -or [Math]::Max($w,$h) -gt 2 * [Math]::Min($w,$h))) { throw 'Screenshots must be 320-3840 pixels with aspect ratio at most 2:1.' }
        } finally { $bitmap.Dispose() }
    }
    [pscustomobject]@{ kind=$Kind; path=$resolved; sha256=(Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant(); length=[long]$item.Length; mime=$mime }
}
function Read-ReleaseText([string]$Path, [int]$Limit) {
    $value = [IO.File]::ReadAllText($Path).Trim()
    if ([string]::IsNullOrWhiteSpace($value) -or $value.Length -gt $Limit) { throw "Empty or over-limit listing text: $([IO.Path]::GetFileName($Path))." }
    return $value
}
function New-ReleasePlan {
    if (!$BundleSha256) { throw 'Provide the independently reviewed bundle SHA-256 with -BundleSha256.' }
    if (@($Screenshots).Count -ne 2) { throw 'Provide exactly two reviewed screenshots using -Screenshots.' }
    $bundleFile = Get-ReleaseFile $Bundle 'bundle'
    if ($bundleFile.sha256 -ne $BundleSha256.ToLowerInvariant()) { throw 'The bundle does not match the explicitly supplied SHA-256.' }
    if ([string]::IsNullOrWhiteSpace($ReleaseName) -or $ReleaseName.Length -gt 50) { throw 'ReleaseName must contain 1-50 characters.' }
    $files = @($bundleFile, (Get-ReleaseFile $Icon 'icon'), (Get-ReleaseFile $FeatureGraphic 'featureGraphic'))
    foreach ($path in $Screenshots) { $files += Get-ReleaseFile $path 'phoneScreenshots' }
    if ($files[3].sha256 -eq $files[4].sha256) { throw 'The two screenshots must not be duplicates.' }
    $listing = [ordered]@{ language='en-US' }
    foreach ($field in @(@('title','title.txt',30), @('shortDescription','short-description.txt',80), @('fullDescription','full-description.txt',4000))) {
        $file = Get-ReleaseFile (Join-Path $ListingDirectory $field[1]) 'text'
        $files += $file
        $listing[$field[0]] = Read-ReleaseText $file.path $field[2]
    }
    $notesFile = Get-ReleaseFile (Join-Path $ListingDirectory 'release-notes.txt') 'text'
    $files += $notesFile
    $notes = Read-ReleaseText $notesFile.path 500
    [pscustomobject]@{
        packageName=$releasePackage; profile=$releaseProfile; versionCode=$VersionCode; releaseName=$ReleaseName;
        track='production'; releaseStatus='draft'; listing=[pscustomobject]$listing; releaseNotes=$notes; files=$files
    }
}
function Assert-ReleaseFiles($Plan) {
    foreach ($file in $Plan.files) {
        $current = Get-ReleaseFile $file.path $file.kind
        if ($current.sha256 -ne $file.sha256 -or $current.length -ne $file.length) { throw 'A staged file changed or disappeared. Review the candidate again; this edit will not be committed.' }
    }
}
function Get-ReleasePlanHash($Plan) {
    $bytes = $releaseUtf8.GetBytes(($Plan | ConvertTo-Json -Depth 20 -Compress))
    $hasher = [Security.Cryptography.SHA256]::Create()
    try { ([BitConverter]::ToString($hasher.ComputeHash($bytes))).Replace('-','').ToLowerInvariant() }
    finally { $hasher.Dispose() }
}
function Save-ReleaseState {
    New-Item -ItemType Directory -Path $releaseLocalRoot -Force | Out-Null
    $temporary = "$releaseStatePath.$([Guid]::NewGuid().ToString('N')).tmp"
    try {
        [IO.File]::WriteAllText($temporary,($releaseState | ConvertTo-Json -Depth 25),$releaseUtf8)
        if ([IO.File]::Exists($releaseStatePath)) { [IO.File]::Replace($temporary,$releaseStatePath,[NullString]::Value) }
        else { [IO.File]::Move($temporary,$releaseStatePath) }
    } finally { if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) } }
}
function Save-NewReleaseEdit($Edit, $Plan, [scriptblock]$Persist = { Save-ReleaseState }) {
    if ($Edit.id -notmatch '^[A-Za-z0-9_-]{1,100}$') { throw 'Google returned an invalid edit ID.' }
    # Track the remote edit before checking any remaining metadata or writing
    # local state, so an initial disk/ACL failure cannot orphan it silently.
    $script:releaseTemporaryId=$Edit.id
    if (!$Edit.expiryTimeSeconds) { throw 'Google returned invalid edit expiry metadata.' }
    $script:releaseState=[pscustomobject]@{
        schemaVersion=1;editId=$Edit.id;expiryTimeSeconds=$Edit.expiryTimeSeconds;
        createdUtc=[DateTimeOffset]::UtcNow.ToString('o');phase='Created';
        planHash=(Get-ReleasePlanHash $Plan);plan=$Plan
    }
    & $Persist
    $script:releaseTemporaryId=$null
}
function Clear-TemporaryReleaseEdit([scriptblock]$Delete = {
    param($Id)
    $null=Invoke-ReleaseApi 'DELETE' "$releaseBase/$Id"
}) {
    if (!$script:releaseTemporaryId) { return }
    try {
        & $Delete $script:releaseTemporaryId
        $script:releaseTemporaryId=$null
    } catch {
        Write-Warning "Temporary edit cleanup failed (edit $script:releaseTemporaryId). No commit was requested; resolve it before other edit work."
    }
}
function Read-ReleaseState {
    if (!(Test-Path -LiteralPath $releaseStatePath -PathType Leaf)) { throw 'No saved edit. Stage with -NewEdit after checking for other publishing work.' }
    $state = [IO.File]::ReadAllText($releaseStatePath) | ConvertFrom-Json
    if ($state.schemaVersion -ne 1 -or $state.plan.packageName -ne $releasePackage -or $state.plan.profile -ne $releaseProfile -or $state.editId -notmatch '^[A-Za-z0-9_-]{1,100}$' -or (Get-ReleasePlanHash $state.plan) -ne $state.planHash) { throw 'Saved edit metadata failed validation.' }
    if (!$releaseEditArgument -or $releaseEditArgument -ne $state.editId) { throw 'Provide -EditId matching the saved edit. No implicit edit selection is allowed.' }
    if ($state.phase -in @('CommittedDraft','CommitUncertain')) { throw 'This edit was committed or its commit outcome is uncertain. Inspect Console before further work; do not blindly retry.' }
    if ([long]$state.expiryTimeSeconds -le [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()) { throw 'The saved edit expired. Inspect Console and archive its local state before creating another edit.' }
    Assert-ReleaseFiles $state.plan
    return $state
}
function Get-ReleaseSnapshot([string]$Id) {
    $uri = "$releaseBase/$Id"
    $images = [ordered]@{}
    foreach ($kind in @('icon','featureGraphic','phoneScreenshots')) {
        $images[$kind] = @(Get-ReleaseArray (Invoke-ReleaseApi 'GET' "$uri/listings/en-US/$kind") 'images')
    }
    [pscustomobject]@{
        bundles=@(Get-ReleaseArray (Invoke-ReleaseApi 'GET' "$uri/bundles") 'bundles');
        listings=@(Get-ReleaseArray (Invoke-ReleaseApi 'GET' "$uri/listings") 'listings');
        tracks=@(Get-ReleaseArray (Invoke-ReleaseApi 'GET' "$uri/tracks") 'tracks');
        images=[pscustomobject]$images
    }
}
function Get-MediaAction($Existing, $Desired, [bool]$Replace) {
    $existingItems = @($Existing); $desiredItems = @($Desired)
    $prefix = $existingItems.Count -le $desiredItems.Count
    if ($prefix) {
        for ($i=0; $i -lt $existingItems.Count; $i++) {
            if (!$existingItems[$i].PSObject.Properties['sha256'] -or $existingItems[$i].sha256 -ne $desiredItems[$i].sha256) { $prefix=$false; break }
        }
    }
    if ($prefix) { return [pscustomobject]@{ delete=$false; skip=$existingItems.Count } }
    if (!$Replace) { throw 'Existing Play media differs from the candidate. Review it and explicitly use -ReplaceExistingMedia if replacement is intended.' }
    return [pscustomobject]@{ delete=$true; skip=0 }
}
function Assert-ProductionDraft($Snapshot, $Plan, [bool]$AllowEmpty) {
    $tracks = @($Snapshot.tracks | Where-Object { $_.track -eq 'production' })
    if ($tracks.Count -gt 1) { throw 'Unexpected duplicate production tracks.' }
    $releases = @(if ($tracks.Count -eq 1) { Get-ReleaseArray $tracks[0] 'releases' })
    if ($AllowEmpty -and $releases.Count -eq 0) { return }
    if ($releases.Count -ne 1 -or $releases[0].status -ne 'draft' -or @($releases[0].versionCodes).Count -ne 1 -or [string]$releases[0].versionCodes[0] -ne [string]$Plan.versionCode) { throw 'Production contains a different release. This first-release helper will not replace or alter it.' }
}
function Assert-RemoteCandidate($Snapshot, $Plan) {
    Assert-ProductionDraft $Snapshot $Plan $false
    $bundleItems = @($Snapshot.bundles | Where-Object { [string]$_.versionCode -eq [string]$Plan.versionCode })
    $expectedBundle = @($Plan.files | Where-Object { $_.kind -eq 'bundle' })[0]
    if ($bundleItems.Count -ne 1 -or $bundleItems[0].sha256 -ne $expectedBundle.sha256) { throw 'Google Play bundle SHA-256 or version code does not match the candidate.' }
    $listingItems = @($Snapshot.listings | Where-Object { $_.language -eq 'en-US' })
    if ($listingItems.Count -ne 1) { throw 'Google Play en-US listing is missing.' }
    foreach ($field in @('title','shortDescription','fullDescription')) {
        if ($listingItems[0].$field -cne $Plan.listing.$field) { throw "Google Play listing differs: $field." }
    }
    foreach ($kind in @('icon','featureGraphic','phoneScreenshots')) {
        $desired = @($Plan.files | Where-Object { $_.kind -eq $kind })
        $action = Get-MediaAction @($Snapshot.images.$kind) $desired $false
        if ($action.skip -ne $desired.Count) { throw "Google Play media is incomplete: $kind." }
    }
    $track = @($Snapshot.tracks | Where-Object { $_.track -eq 'production' })[0]
    $release = $track.releases[0]
    $notes = @(Get-ReleaseArray $release 'releaseNotes')
    if ($release.name -cne $Plan.releaseName -or $notes.Count -ne 1 -or $notes[0].language -ne 'en-US' -or $notes[0].text -cne $Plan.releaseNotes) { throw 'Google Play release name or notes differ from the candidate.' }
}
function Invoke-ReleaseSelfTest {
    $checks = 0
    foreach ($case in @(
        @('GET',"$releaseBase/123/tracks"),
        @('POST',"$releaseBase/123`:validate"),
        @('POST',"$releaseBase/123`:commit?changesNotSentForReview=true&changesInReviewBehavior=ERROR_IF_IN_REVIEW"),
        @('POST',"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$releasePackage/edits/123/bundles?uploadType=media")
    )) { Assert-ReleaseEndpoint $case[0] $case[1]; $checks++ }
    foreach ($case in @(
        @('POST',"$releaseBase/123`:commit"),
        @('POST',"$releaseBase/123`:commit?changesNotSentForReview=false"),
        @('GET','https://evil.example/androidpublisher/v3/applications/com.airangelvl/edits/123'),
        @('GET',"${releaseBase}evil/123"),
        @('DELETE',"$releaseBase/123/tracks"),
        @('PUT',"$releaseBase/123/tracks/internal"),
        @('GET',"$releaseBase/123/../tracks")
    )) {
        $rejected=$false
        try { Assert-ReleaseEndpoint $case[0] $case[1] } catch { $rejected=$true }
        if (!$rejected) { throw 'Unsafe endpoint accepted.' }; $checks++
    }
    $a=[pscustomobject]@{sha256=('a'*64)}; $b=[pscustomobject]@{sha256=('b'*64)}
    if ((Get-MediaAction @($a) @($a,$b) $false).skip -ne 1) { throw 'Partial media resume failed.' }; $checks++
    $rejected=$false
    try { $null=Get-MediaAction @($b) @($a) $false } catch { $rejected=$true }
    if (!$rejected) { throw 'Unapproved media replacement accepted.' }; $checks++
    if (!(Get-MediaAction @($b) @($a) $true).delete) { throw 'Explicit replacement failed.' }; $checks++
    $plan=[pscustomobject]@{versionCode=3}
    $snapshot=[pscustomobject]@{tracks=@([pscustomobject]@{track='production';releases=@([pscustomobject]@{status='completed';versionCodes=@('3')})})}
    $rejected=$false
    try { Assert-ProductionDraft $snapshot $plan $false } catch { $rejected=$true }
    if (!$rejected) { throw 'Completed production release accepted.' }; $checks++
    $snapshot.tracks[0].releases[0].status='draft'
    Assert-ProductionDraft $snapshot $plan $false; $checks++
    $folder=Join-Path ([IO.Path]::GetTempPath()) ('codex-play-release-test-'+[Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $folder | Out-Null
    $filePath=Join-Path $folder 'fixture.txt'
    try {
        [IO.File]::WriteAllText($filePath,'before',$releaseUtf8)
        $file=Get-ReleaseFile $filePath 'text'
        $fixture=[pscustomobject]@{files=@($file)}
        Assert-ReleaseFiles $fixture; $checks++
        [IO.File]::WriteAllText($filePath,'after',$releaseUtf8)
        $rejected=$false
        try { Assert-ReleaseFiles $fixture } catch { $rejected=$true }
        if (!$rejected) { throw 'Changed file accepted.' }; $checks++
        if ((Get-ReleaseError '{"error":{"message":"Bearer secret-token"}}' 400) -match 'secret-token') { throw 'Sensitive error was not suppressed.' }; $checks++
    } finally {
        if ([IO.File]::Exists($filePath)) { [IO.File]::Delete($filePath) }
        [IO.Directory]::Delete($folder,$false)
    }
    $testEdit=[pscustomobject]@{id='offline-edit';expiryTimeSeconds='9999999999'}
    $script:releaseTemporaryId=$null
    $script:releaseTestDeletedId=$null
    $rejected=$false
    try { Save-NewReleaseEdit $testEdit $plan { throw 'Injected initial state write failure.' } }
    catch { $rejected=$true }
    if (!$rejected -or $script:releaseTemporaryId -ne 'offline-edit') { throw 'Initial save failure lost the remote edit ID.' }; $checks++
    Clear-TemporaryReleaseEdit {param($Id) $script:releaseTestDeletedId=$Id}
    if ($script:releaseTestDeletedId -ne 'offline-edit' -or $script:releaseTemporaryId) { throw 'Initial save failure did not clean up its remote edit.' }; $checks++
    $script:releaseTemporaryId='offline-edit'
    Clear-TemporaryReleaseEdit {param($Id) throw 'Injected cleanup failure.'} 3>$null
    if ($script:releaseTemporaryId -ne 'offline-edit') { throw 'Failed cleanup lost the edit ID.' }; $checks++
    $script:releaseTemporaryId=$null
    $script:releaseState=$null
    $script:releaseTestDeletedId=$null
    [pscustomobject]@{result='Passed';checks=$checks;networkRequests=0;credentialsRead=$false}
}

if ($releaseCommand -eq 'SelfTest') { Invoke-ReleaseSelfTest; return }
if ($releaseCommand -eq 'CheckFiles') {
    $plan=New-ReleasePlan
    [pscustomobject]@{result='FilesChecked';packageName=$releasePackage;versionCode=$plan.versionCode;planHash=(Get-ReleasePlanHash $plan);files=$plan.files;networkRequests=0}
    return
}
if ($releaseCommand -eq 'ReadSnapshot' -and (!$ConfirmNoOtherEdit -or $NewEdit -or $releaseEditArgument)) { throw 'ReadSnapshot requires -ConfirmNoOtherEdit and cannot take -NewEdit or -EditId.' }
if ($releaseCommand -eq 'Stage' -and ($NewEdit.IsPresent -eq [bool]$releaseEditArgument)) { throw 'Stage requires exactly one of -NewEdit or -EditId.' }
if ($NewEdit -and !$ConfirmNoOtherEdit) { throw 'Creating an edit requires -ConfirmNoOtherEdit after checking no other publishing work is active.' }
if ($releaseCommand -notin @('Stage','ReadSnapshot') -and ($NewEdit -or !$releaseEditArgument)) { throw 'Provide -EditId for the saved edit; this command never creates one.' }
$releasePlan = if ($releaseCommand -eq 'Stage') { New-ReleasePlan } else { $null }
$releaseMutex = New-Object Threading.Mutex($false,"Local\Codex.GooglePlay.Release.$releaseProfile.$releasePackage")
$releaseLocked=$false
$releaseTemporaryId=$null
$releaseAccount=$null
try {
    try { $releaseLocked=$releaseMutex.WaitOne(0) } catch [Threading.AbandonedMutexException] { $releaseLocked=$true }
    if (!$releaseLocked) { throw 'Another release-helper process is running for this app.' }
    if ($NewEdit -or $releaseCommand -eq 'ReadSnapshot') {
        if (Test-Path -LiteralPath $releaseStatePath) { throw 'Saved edit metadata exists. Inspect it and Console; archive the resolved state before opening another edit.' }
    } else {
        $releaseState=Read-ReleaseState
        if ($releaseCommand -eq 'Stage' -and (Get-ReleasePlanHash $releasePlan) -ne $releaseState.planHash) { throw 'The resumed plan differs from the saved plan.' }
    }
    # Dot sourcing Status defines the established DPAPI/JWT helpers. It may
    # assign its own Command/Profile variables, so this script uses release*
    # bindings for command dispatch. Status output is deliberately discarded.
    . (Join-Path $PSScriptRoot 'google-play.ps1') -Command Status -Profile $releaseProfile | Out-Null
    $releaseConfig=Read-LocalConfig
    if ($releaseConfig.packageName -ne $releasePackage) { throw 'The local profile is configured for a different package.' }
    $releaseAccount=Read-LocalServiceAccount $releaseConfig
    $releaseToken=Get-AccessToken $releaseAccount
    $releaseAccount=$null
    if ($releaseCommand -eq 'ReadSnapshot') {
        $edit=Invoke-ReleaseApi 'POST' $releaseBase @{}
        if ($edit.id -notmatch '^[A-Za-z0-9_-]{1,100}$') { throw 'Google returned an invalid edit ID.' }
        $releaseTemporaryId=$edit.id
        $snapshot=Get-ReleaseSnapshot $edit.id
        $null=Invoke-ReleaseApi 'DELETE' "$releaseBase/$($edit.id)"
        $releaseTemporaryId=$null
        [pscustomobject]@{packageName=$releasePackage;temporaryEditDeleted=$true;snapshot=$snapshot;published=$false}
        return
    }
    if ($NewEdit) {
        $edit=Invoke-ReleaseApi 'POST' $releaseBase @{}
        Save-NewReleaseEdit $edit $releasePlan

    } else {
        $liveEdit=Invoke-ReleaseApi 'GET' "$releaseBase/$($releaseState.editId)"
        if ($liveEdit.id -ne $releaseState.editId) { throw 'Google did not return the saved edit.' }
    }
    $id=$releaseState.editId
    $uri="$releaseBase/$id"
    $plan=$releaseState.plan
    if ($releaseCommand -eq 'Stage') {
        $snapshot=Get-ReleaseSnapshot $id
        Assert-ProductionDraft $snapshot $plan $true
        $bundleFile=@($plan.files | Where-Object {$_.kind -eq 'bundle'})[0]
        $existingBundle=@($snapshot.bundles | Where-Object {[string]$_.versionCode -eq [string]$plan.versionCode})
        if ($existingBundle.Count -gt 1 -or ($existingBundle.Count -eq 1 -and $existingBundle[0].sha256 -ne $bundleFile.sha256)) { throw 'This version code already has different bundle bytes in Play.' }
        $actions=@{}
        foreach ($kind in @('icon','featureGraphic','phoneScreenshots')) {
            $actions[$kind]=Get-MediaAction @($snapshot.images.$kind) @($plan.files | Where-Object {$_.kind -eq $kind}) $ReplaceExistingMedia.IsPresent
        }
        $releaseState.phase='Staging'; Save-ReleaseState
        if ($existingBundle.Count -eq 0) {
            $uploaded=Invoke-ReleaseApi 'POST' "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$releasePackage/edits/$id/bundles?uploadType=media" -File $bundleFile
            if ([string]$uploaded.versionCode -ne [string]$plan.versionCode -or $uploaded.sha256 -ne $bundleFile.sha256) { throw 'Uploaded bundle verification failed. The edit remains uncommitted.' }
        }
        $null=Invoke-ReleaseApi 'PUT' "$uri/listings/en-US" $plan.listing
        foreach ($kind in @('icon','featureGraphic','phoneScreenshots')) {
            $action=$actions[$kind]
            if ($action.delete) { $null=Invoke-ReleaseApi 'DELETE' "$uri/listings/en-US/$kind" }
            $desired=@($plan.files | Where-Object {$_.kind -eq $kind})
            for ($index=$action.skip; $index -lt $desired.Count; $index++) {
                $uploaded=Invoke-ReleaseApi 'POST' "https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$releasePackage/edits/$id/listings/en-US/${kind}?uploadType=media" -File $desired[$index]
                if (!$uploaded.image -or $uploaded.image.sha256 -ne $desired[$index].sha256) { throw 'Uploaded image verification failed. The edit remains uncommitted.' }
            }
        }
        $track=@{track='production';releases=@(@{name=$plan.releaseName;versionCodes=@([string]$plan.versionCode);status='draft';releaseNotes=@(@{language='en-US';text=$plan.releaseNotes})})}
        $null=Invoke-ReleaseApi 'PUT' "$uri/tracks/production" $track
        Assert-RemoteCandidate (Get-ReleaseSnapshot $id) $plan
        Assert-ReleaseFiles $plan
        $releaseState.phase='Staged'; Save-ReleaseState
        [pscustomobject]@{result='Staged';editId=$id;statePath=$releaseStatePath;track='production';status='draft';committed=$false}
        return
    }
    if ($releaseState.phase -notin @('Staged','Validated')) { throw 'The edit is not fully staged. Resume Stage with the exact original inputs first.' }
    Assert-RemoteCandidate (Get-ReleaseSnapshot $id) $plan
    $null=Invoke-ReleaseApi 'POST' "$uri`:validate"
    Assert-ReleaseFiles $plan
    $releaseState.phase='Validated'; Save-ReleaseState
    if ($releaseCommand -eq 'Validate') {
        [pscustomobject]@{result='Validated';editId=$id;versionCode=$plan.versionCode;track='production';status='draft';committed=$false}
        return
    }
    # Save an uncertainty marker before sending the only commit request. A
    # timeout must not lead a future session to blindly repeat the commit.
    $releaseState.phase='CommitUncertain'; Save-ReleaseState
    $null=Invoke-ReleaseApi 'POST' "$uri`:commit?changesNotSentForReview=true&changesInReviewBehavior=ERROR_IF_IN_REVIEW"
    $releaseState.phase='CommittedDraft'; Save-ReleaseState
    [pscustomobject]@{result='CommittedDraft';editId=$id;versionCode=$plan.versionCode;track='production';status='draft';changesNotSentForReview=$true;productionRolloutStarted=$false;statePath=$releaseStatePath}
} finally {
    if ($releaseTemporaryId -and $releaseToken) {
        Clear-TemporaryReleaseEdit
    }
    $releaseAccount=$null; $releaseToken=$null
    if ($releaseLocked) { $releaseMutex.ReleaseMutex() }
    $releaseMutex.Dispose()
}