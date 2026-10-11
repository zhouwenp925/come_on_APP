param([ValidateSet('Debug','Release','Check')][string]$Mode = 'Debug', [string]$JdkPath = $env:JAVA_HOME)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (!$JdkPath -and (Test-Path (Join-Path $projectRoot '.tools\jdk17'))) { $JdkPath = (Get-ChildItem (Join-Path $projectRoot '.tools\jdk17') -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'bin\jlink.exe') } | Select-Object -First 1).FullName }
if (!$JdkPath) { throw 'Install JDK 17+ and set JAVA_HOME, or run Bootstrap-Android.ps1.' }
if (!(Test-Path (Join-Path $JdkPath 'bin\jlink.exe'))) { throw 'Use a complete JDK 17+ containing javac and jlink; an IDE runtime alone is insufficient.' }
$JdkPath = (Resolve-Path -LiteralPath $JdkPath).Path
$env:JAVA_HOME = $JdkPath
if ($Mode -eq 'Release') { & (Join-Path $PSScriptRoot 'New-SigningKey.ps1') -JdkPath $JdkPath }
$gradleMain = Join-Path $projectRoot '.tools\gradle-8.13\lib\gradle-launcher-8.13.jar'
$tasks = switch ($Mode) { 'Debug' { @(':core:test',':app:assembleDebug') }; 'Release' { @(':core:test',':app:lintRelease',':app:assembleRelease') }; 'Check' { @(':core:test',':app:lintDebug',':app:testDebugUnitTest',':app:assembleDebug') } }
$savedErrorPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue' # Native stderr may contain harmless JVM/SDK warnings.
    if (Test-Path $gradleMain) { & (Join-Path $JdkPath 'bin\java.exe') -classpath $gradleMain org.gradle.launcher.GradleMain -p $projectRoot @tasks --console=plain }
    else { & (Join-Path $projectRoot 'gradlew.bat') -p $projectRoot @tasks --console=plain }
    $buildExit = $LASTEXITCODE
} finally { $ErrorActionPreference = $savedErrorPreference }
if ($buildExit -ne 0) { throw 'Build checks failed. APK is not ready.' }
if ($Mode -ne 'Check') {
    $variant = $Mode.ToLowerInvariant()
    $source = Join-Path $projectRoot "app\build\outputs\apk\$variant\app-$variant.apk"
    $artifacts = Join-Path $projectRoot 'artifacts'
    New-Item -ItemType Directory -Path $artifacts -Force | Out-Null
    $version = (Get-Content (Join-Path $projectRoot 'version.properties') | Where-Object { $_ -like 'versionName=*' }).Substring(12)
    $destination = Join-Path $artifacts "shouxin-$version-$variant.apk"
    Copy-Item -LiteralPath $source -Destination $destination -Force
    $checksum = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($destination + '.sha256', $checksum + '  ' + [IO.Path]::GetFileName($destination) + "`n")
    Write-Output ('APK: ' + $destination)
}
