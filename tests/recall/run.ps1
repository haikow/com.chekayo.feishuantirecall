<# Run build.ps1 (or build.sh) first. Native tests use only a fresh synthetic database on the
   explicitly selected arm64 Android device; no installed app data is opened. #>
param(
    [string]$Serial = '',
    [string]$SdkRoot = '',
    [string]$NdkPath = '',
    [string]$AndroidJar = ''
)
$ErrorActionPreference = 'Stop'
$recallRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Push-Location $recallRoot
try {
    if (-not (Test-Path 'build/app') -or -not (Test-Path 'build/stubs')) {
        throw 'Run build.ps1 (or build.sh) first so build/app and build/stubs exist.'
    }
    if (-not $SdkRoot) {
        foreach ($cand in @(
            $env:ANDROID_HOME,
            $env:ANDROID_SDK_ROOT,
            $(if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }),
            $(if ($HOME) { Join-Path $HOME 'Android/Sdk' }),
            $(if ($HOME) { Join-Path $HOME 'Library/Android/sdk' }),
            '/opt/android-sdk'
        )) {
            if ($cand -and (Test-Path $cand)) { $SdkRoot = $cand; break }
        }
    }
    if (-not $AndroidJar -and $SdkRoot -and (Test-Path (Join-Path $SdkRoot 'platforms'))) {
        $foundJar = Get-ChildItem (Join-Path $SdkRoot 'platforms') -Filter android.jar -Recurse |
            Sort-Object FullName -Descending | Select-Object -First 1
        if ($foundJar) { $AndroidJar = $foundJar.FullName }
    }
    if (-not $AndroidJar -or -not (Test-Path $AndroidJar)) {
        throw 'android.jar not found; set ANDROID_HOME or pass -AndroidJar.'
    }

    $sep = [IO.Path]::PathSeparator
    $recallOut = Join-Path $recallRoot 'build/recall-tests'
    if (Test-Path $recallOut) { Remove-Item $recallOut -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $recallOut | Out-Null

    $stubOverrides = Get-ChildItem 'tests/recall/stubs' -Recurse -Filter '*.java' | ForEach-Object { $_.FullName }
    & javac -encoding UTF-8 -source 8 -target 8 -d $recallOut $stubOverrides
    if ($LASTEXITCODE) { throw 'Test stub compilation failed.' }

    $baseCp = "build/app${sep}build/stubs${sep}$AndroidJar"
    $testCp = "$recallOut${sep}$baseCp"
    $recallTests = @(
        'tests/recall/RecallBehaviorTest.java',
        'tests/recall/RecallAuditRegressionTest.java'
    )
    & javac -encoding UTF-8 -source 8 -target 8 -cp $testCp -d $recallOut $recallTests
    if ($LASTEXITCODE) { throw 'Java test compilation failed.' }

    foreach ($recallTest in @('RecallBehaviorTest', 'RecallAuditRegressionTest')) {
        & java -cp $testCp "com.chekayo.feishuantirecall.$recallTest"
        if ($LASTEXITCODE) { throw "$recallTest failed." }
    }

    if ($Serial) {
        if ($Serial -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'Invalid device serial.' }
        if (-not $NdkPath) {
            if ($env:ANDROID_NDK_HOME -and (Test-Path $env:ANDROID_NDK_HOME)) { $NdkPath = $env:ANDROID_NDK_HOME }
            elseif ($SdkRoot -and (Test-Path (Join-Path $SdkRoot 'ndk'))) {
                $NdkPath = (Get-ChildItem (Join-Path $SdkRoot 'ndk') -Directory |
                    Sort-Object Name -Descending | Select-Object -First 1).FullName
            }
        }
        $hostTag = if ($IsLinux) { 'linux-x86_64' } elseif ($IsMacOS) { 'darwin-x86_64' } else { 'windows-x86_64' }
        $clangName = if ($hostTag -eq 'windows-x86_64') { 'clang++.exe' } else { 'clang++' }
        $recallClang = Join-Path $NdkPath "toolchains/llvm/prebuilt/$hostTag/bin/$clangName"
        $recallBinary = Join-Path $recallOut 'native-test'
        & $recallClang --target=aarch64-linux-android29 -std=c++17 -O2 -static-libstdc++ -fPIE -pie `
            -o $recallBinary tests/recall/native_test.cpp -ldl
        if ($LASTEXITCODE) { throw 'Native test compilation failed.' }
        $recallDeviceDir = '/data/local/tmp/feishukit-recall-test-' + [guid]::NewGuid().ToString('N')
        & adb -s $Serial shell mkdir $recallDeviceDir
        if ($LASTEXITCODE) { throw 'Cannot create device test directory.' }
        & adb -s $Serial push $recallBinary "$recallDeviceDir/native-test"
        if ($LASTEXITCODE) { throw 'Cannot push native test.' }
        & adb -s $Serial shell "chmod 755 $recallDeviceDir/native-test; cd $recallDeviceDir && ./native-test /system/lib64/libsqlite.so"
        if ($LASTEXITCODE) { throw "Native tests failed; artifacts retained in $recallDeviceDir" }
        Write-Host "Synthetic test database: $recallDeviceDir"
    }
} finally { Pop-Location }
