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
    if (!(Get-Command python -ErrorAction SilentlyContinue)) { throw 'Install Python 3 or GitHub CLI to publish, or use GitHub Releases in a browser.' }
    $publishArgs = @((Join-Path $PSScriptRoot 'publish_release.py'),'--repository',$Repository)
    if ($Draft) { $publishArgs += '--draft' }
    & python @publishArgs
    if ($LASTEXITCODE -ne 0) { throw 'Release publication failed; inspect the release page before retrying.' }
}
Write-Output "Release: https://github.com/$Repository/releases/tag/v$version"
