param([string]$Repository = 'zhouwenp925/come_on_APP', [switch]$Draft)
$ErrorActionPreference = 'Stop'
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid repository' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$version = (Get-Content (Join-Path $projectRoot 'version.properties') | Where-Object { $_ -like 'versionName=*' }).Substring(12)
if ($version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid stable version' }
$apk = Join-Path $projectRoot "artifacts\shouxin-$version-release.apk"
$checksumFile = $apk + '.sha256'
if (!(Test-Path -LiteralPath $apk) -or !(Test-Path -LiteralPath $checksumFile)) { throw 'Run scripts/Build.ps1 -Mode Release first.' }
$actual = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
$expected = (Get-Content -LiteralPath $checksumFile).Split(' ')[0]
if ($actual -ne $expected) { throw 'APK checksum changed; rebuild before publishing.' }
$localVersion = [IO.File]::ReadAllText((Join-Path $projectRoot 'version.properties'))
if (Get-Command gh -ErrorAction SilentlyContinue) {
    $encodedVersion = & gh api "repos/$Repository/contents/version.properties?ref=main" --jq '.content'
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read repository version. Commit and push source first.' }
    $remoteVersion = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(($encodedVersion -join '').Replace("`n",'')))
    if ($remoteVersion.Trim() -ne $localVersion.Trim()) { throw 'Remote main version differs. Push the version change before publishing.' }
    $arguments = @('release','create',"v$version",$apk,$checksumFile,'--repo',$Repository,'--target','main','--title',"Shouxin $version",'--notes-file',(Join-Path $projectRoot 'CHANGELOG.md'))
    if ($Draft) { $arguments += '--draft' }
    & gh @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Release publication failed; inspect the release page before retrying.' }
} else {
    # Reuse the user's existing Git Credential Manager login. Never echo or persist credentials.
    $env:GIT_TERMINAL_PROMPT = '0'; $env:GCM_INTERACTIVE = 'never'
    $processInfo = New-Object Diagnostics.ProcessStartInfo
    $processInfo.FileName = (Get-Command git).Source
    $processInfo.Arguments = 'credential fill'
    $processInfo.UseShellExecute = $false; $processInfo.CreateNoWindow = $true
    $processInfo.RedirectStandardInput = $true; $processInfo.RedirectStandardOutput = $true; $processInfo.RedirectStandardError = $true
    $credentialProcess = [Diagnostics.Process]::Start($processInfo)
    $inputBytes = [Text.Encoding]::UTF8.GetBytes("protocol=https`nhost=github.com`npath=$Repository.git`n`n")
    $credentialProcess.StandardInput.BaseStream.Write($inputBytes,0,$inputBytes.Length)
    $credentialProcess.StandardInput.BaseStream.Flush(); $credentialProcess.StandardInput.Close()
    $credentialText = $credentialProcess.StandardOutput.ReadToEnd()
    $credentialError = $credentialProcess.StandardError.ReadToEnd()
    $credentialProcess.WaitForExit()
    if ($credentialProcess.ExitCode -ne 0) { throw ('Git login unavailable: ' + ($credentialError.Split("`n") | Select-Object -First 1)) }
    $credentialLines = $credentialText.Trim().Split("`n")
    $credential = @{}
    foreach ($line in $credentialLines) { if ($line.TrimEnd("`r") -match '^([^=]+)=(.*)$') { $credential[$matches[1]] = $matches[2] } }
    if (!$credential['password']) { throw 'No GitHub credential is available.' }
    $headers = @{ Authorization = 'Bearer ' + $credential['password']; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28' }
    try {
        $remote = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/contents/version.properties?ref=main" -Headers $headers
        $remoteVersion = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($remote.content))
        if ($remoteVersion.Trim() -ne $localVersion.Trim()) { throw 'VERSION_MISMATCH' }
        $head = Invoke-RestMethod -Uri "https://api.github.com/repos/$Repository/commits/main" -Headers $headers
        $body = @{ tag_name="v$version"; target_commitish=$head.sha; name="Shouxin $version"; body=[IO.File]::ReadAllText((Join-Path $projectRoot 'CHANGELOG.md')); draft=$true; prerelease=$false } | ConvertTo-Json
        $release = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$Repository/releases" -Headers $headers -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
        foreach ($file in @($apk,$checksumFile)) {
            $name = [Uri]::EscapeDataString([IO.Path]::GetFileName($file))
            $upload = "https://uploads.github.com/repos/$Repository/releases/$($release.id)/assets?name=$name"
            Invoke-RestMethod -Method Post -Uri $upload -Headers $headers -ContentType 'application/octet-stream' -InFile $file | Out-Null
        }
        if (!$Draft) { Invoke-RestMethod -Method Patch -Uri "https://api.github.com/repos/$Repository/releases/$($release.id)" -Headers $headers -ContentType 'application/json' -Body '{"draft":false}' | Out-Null }
        Write-Output ('Release ID: ' + $release.id)
    } catch {
        # Do not print request objects, headers, or tokens from a failed HTTP operation.
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        throw "Release API failed (HTTP $status). Check login, remote version and existing release/draft before retrying."
    } finally { $credential.Clear(); $headers.Clear(); $credentialLines = $null; $credentialText = $null; $credentialProcess.Dispose() }
}
Write-Output "Release: https://github.com/$Repository/releases/tag/v$version"
