param([string]$JdkPath = '', [switch]$AcceptSdkLicenses, [string]$GradleMirror = 'https://downloads.gradle.org/distributions/gradle-8.13-bin.zip')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$toolRoot = Join-Path $projectRoot '.tools'
New-Item -ItemType Directory -Path $toolRoot -Force | Out-Null
function Fetch-Checked([string]$Uri, [string]$File, [string]$Sha256) {
    if (!(Test-Path -LiteralPath $File) -or (Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Sha256.Trim().ToLowerInvariant()) {
        Write-Output ('Downloading ' + [IO.Path]::GetFileName($File))
        & curl.exe --fail --location --retry 3 --retry-all-errors --silent --show-error --output $File $Uri
        if ($LASTEXITCODE -ne 0) { throw 'Download failed' }
    }
    if ((Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Sha256.Trim().ToLowerInvariant()) { throw ('Checksum mismatch: ' + $File) }
}
if (!$JdkPath) {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\jlink.exe'))) { $JdkPath = $env:JAVA_HOME }
    elseif (Test-Path (Join-Path $toolRoot 'jdk17\jdk-17.0.20.1+1\bin\jlink.exe')) { $JdkPath = Join-Path $toolRoot 'jdk17\jdk-17.0.20.1+1' }
    else {
        $jdkUrl = 'https://aka.ms/download-jdk/microsoft-jdk-17.0.20.1-windows-x64.zip'
        & curl.exe --fail --location --retry 3 --silent --show-error --output (Join-Path $toolRoot 'microsoft-jdk.sha256') ($jdkUrl + '.sha256sum.txt')
        if ($LASTEXITCODE -ne 0) { throw 'JDK checksum download failed' }
        $jdkHash = (Get-Content -LiteralPath (Join-Path $toolRoot 'microsoft-jdk.sha256')).Split(' ')[0]
        Fetch-Checked $jdkUrl (Join-Path $toolRoot 'microsoft-jdk.zip') $jdkHash
        Expand-Archive -LiteralPath (Join-Path $toolRoot 'microsoft-jdk.zip') -DestinationPath (Join-Path $toolRoot 'jdk17') -Force
        $JdkPath = Join-Path $toolRoot 'jdk17\jdk-17.0.20.1+1'
    }
}
if (!(Test-Path (Join-Path $JdkPath 'bin\jlink.exe'))) { throw 'Use a complete JDK containing javac and jlink.' }
$env:JAVA_HOME = $JdkPath
$env:PATH = (Join-Path $JdkPath 'bin') + ';' + $env:PATH
$gradleZip = Join-Path $toolRoot 'gradle-8.13-bin.zip'
$gradleHashResponse = (Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256' -UseBasicParsing).Content
$gradleHash = if ($gradleHashResponse -is [byte[]]) { [Text.Encoding]::UTF8.GetString($gradleHashResponse).Trim() } else { ([string]$gradleHashResponse).Trim() }
Fetch-Checked $GradleMirror $gradleZip $gradleHash
if (!(Test-Path (Join-Path $toolRoot 'gradle-8.13'))) { Expand-Archive -LiteralPath $gradleZip -DestinationPath $toolRoot }
$sdkRoot = Join-Path $toolRoot 'android-sdk'
$sdkTools = Join-Path $sdkRoot 'cmdline-tools\latest'
if (!(Test-Path (Join-Path $sdkTools 'bin\sdkmanager.bat'))) {
    $sdkZip = Join-Path $toolRoot 'android-cli.zip'
    Fetch-Checked 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip' $sdkZip '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'
    $sdkExtract = Join-Path $toolRoot 'sdk-extract'
    if (!(Test-Path $sdkExtract)) { Expand-Archive -LiteralPath $sdkZip -DestinationPath $sdkExtract }
    New-Item -ItemType Directory -Path (Split-Path -Parent $sdkTools) -Force | Out-Null
    $sdkSource = [IO.Path]::GetFullPath((Join-Path $sdkExtract 'cmdline-tools'))
    $sdkDestination = [IO.Path]::GetFullPath($sdkTools)
    if (!$sdkSource.StartsWith([IO.Path]::GetFullPath($toolRoot) + '\') -or !$sdkDestination.StartsWith([IO.Path]::GetFullPath($toolRoot) + '\')) { throw 'SDK paths escaped project tools directory' }
    Move-Item -LiteralPath $sdkSource -Destination $sdkDestination
}
$env:ANDROID_HOME = $sdkRoot
if ($AcceptSdkLicenses) {
    Write-Output 'Review and accept the SDK license prompts in this terminal.'
    & (Join-Path $sdkTools 'bin\sdkmanager.bat') "--sdk_root=$sdkRoot" --licenses
}
& (Join-Path $sdkTools 'bin\sdkmanager.bat') "--sdk_root=$sdkRoot" 'platforms;android-36' 'build-tools;35.0.0' 'platform-tools'
if ($LASTEXITCODE -ne 0 -or !(Test-Path (Join-Path $sdkRoot 'platforms\android-36\android.jar')) -or !(Test-Path (Join-Path $sdkRoot 'build-tools\35.0.0\aapt2.exe'))) { throw 'SDK installation failed. Accept SDK licenses before installing packages.' }
$localSettings = 'sdk.dir=' + $sdkRoot.Replace('\','/')
[IO.File]::WriteAllText((Join-Path $projectRoot 'local.properties'), $localSettings + "`n", [Text.UTF8Encoding]::new($false))
& (Join-Path $JdkPath 'bin\java.exe') -classpath (Join-Path $toolRoot 'gradle-8.13\lib\gradle-launcher-8.13.jar') org.gradle.launcher.GradleMain -p $projectRoot wrapper --gradle-version 8.13 --distribution-type bin --gradle-distribution-sha256-sum $gradleHash
if ($LASTEXITCODE -ne 0) { throw 'Gradle wrapper generation failed' }
Write-Output 'Toolchain ready. Keep JAVA_HOME set to the JDK 17+ path for builds.'
