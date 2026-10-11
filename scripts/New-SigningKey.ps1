param([string]$JdkPath = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$privateRoot = Join-Path $projectRoot '.signing'
$configFile = Join-Path $projectRoot 'signing.properties'
if (Test-Path -LiteralPath $configFile) { Write-Output 'Existing signing configuration preserved. Never regenerate a key for installed users.'; exit 0 }
if (!(Test-Path (Join-Path $JdkPath 'bin\keytool.exe'))) { throw 'Set JdkPath to a JDK 17+ directory.' }
New-Item -ItemType Directory -Path $privateRoot -Force | Out-Null
$keyFile = Join-Path $privateRoot 'shouxin-release.jks'
if (Test-Path -LiteralPath $keyFile) { throw 'Key already exists but configuration is missing. Recover its password rather than replace the key.' }
$randomBytes = New-Object byte[] 32
$crypto = [Security.Cryptography.RandomNumberGenerator]::Create(); $crypto.GetBytes($randomBytes); $crypto.Dispose()
$generatedPassword = [Convert]::ToBase64String($randomBytes)
$env:SHOUXIN_SIGNING_PASSWORD = $generatedPassword
try {
    & (Join-Path $JdkPath 'bin\keytool.exe') -genkeypair -keystore $keyFile -storetype JKS -alias shouxin -keyalg RSA -keysize 3072 -validity 10000 -storepass:env SHOUXIN_SIGNING_PASSWORD -keypass:env SHOUXIN_SIGNING_PASSWORD -dname 'CN=Shouxin Android, OU=Mobile, O=Shouxin, C=CN'
    if ($LASTEXITCODE -ne 0) { throw 'Key generation failed' }
    $text = "SIGNING_STORE_FILE=.signing/shouxin-release.jks`nSIGNING_STORE_PASSWORD=$generatedPassword`nSIGNING_KEY_ALIAS=shouxin`nSIGNING_KEY_PASSWORD=$generatedPassword`n"
    [IO.File]::WriteAllText($configFile, $text, [Text.UTF8Encoding]::new($false))
    Write-Output 'Release key created in .signing/. Back up it and signing.properties privately; both are ignored by Git.'
} finally { Remove-Item Env:\SHOUXIN_SIGNING_PASSWORD -ErrorAction SilentlyContinue }
