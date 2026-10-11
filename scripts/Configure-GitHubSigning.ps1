param([string]$Repository = 'zhouwenp925/come_on_APP')
$ErrorActionPreference = 'Stop'
if ($Repository -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') { throw 'Invalid repository' }
if (!(Get-Command gh -ErrorAction SilentlyContinue)) { throw 'Install GitHub CLI and run gh auth login first.' }
$projectRoot = Split-Path -Parent $PSScriptRoot
$config = @{}
Get-Content -LiteralPath (Join-Path $projectRoot 'signing.properties') | ForEach-Object {
    if ($_ -match '^([^=]+)=(.*)$') { $config[$matches[1]] = $matches[2] }
}
$key = Join-Path $projectRoot $config['SIGNING_STORE_FILE']
$values = @{
    SIGNING_KEYSTORE_BASE64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($key))
    SIGNING_STORE_PASSWORD = $config['SIGNING_STORE_PASSWORD']
    SIGNING_KEY_ALIAS = $config['SIGNING_KEY_ALIAS']
    SIGNING_KEY_PASSWORD = $config['SIGNING_KEY_PASSWORD']
}
Write-Output "Sending the existing release key to encrypted Actions secrets in $Repository. Never commit the key to source."
foreach ($name in $values.Keys) {
    if (!$values[$name]) { throw ('Missing signing value: ' + $name) }
    $values[$name] | & gh secret set $name --repo $Repository
    if ($LASTEXITCODE -ne 0) { throw ('Secret configuration failed: ' + $name) }
}
Write-Output 'Signing secrets configured. Use the same key for every future release.'
