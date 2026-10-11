param([Parameter(Mandatory)][string]$VersionName)
$ErrorActionPreference = 'Stop'
if ($VersionName -notmatch '^\d+\.\d+\.\d+$') { throw 'Use a stable numeric version such as 0.1.1.' }
$file = Join-Path (Split-Path -Parent $PSScriptRoot) 'version.properties'
$values = ConvertFrom-StringData (Get-Content -LiteralPath $file -Raw)
$old = [Version]$values.versionName; $new = [Version]$VersionName
if ($new -le $old) { throw 'New version must be higher. Do not downgrade installed users.' }
$code = [int]$values.versionCode + 1
[IO.File]::WriteAllText($file, "versionCode=$code`nversionName=$VersionName`n", [Text.UTF8Encoding]::new($false))
Write-Output ('Version updated: ' + $VersionName + ' (' + $code + ')')
