<#
.SYNOPSIS
  飞书防撤回 Xposed 模块构建脚本（可移植版）

.DESCRIPTION
  自动探测 NDK / Android SDK / JDK / Keystore，无需硬编码绝对路径。
  clone 后只需系统装有这些工具即可一键构建。

.PARAMETER NdkPath
  手动指定 Android NDK 根目录（覆盖自动探测）

.PARAMETER SdkRoot
  手动指定 Android SDK 根目录（覆盖自动探测）

.PARAMETER JdkHome
  手动指定 JDK 根目录（覆盖自动探测）

.PARAMETER AndroidJar
  手动指定 android.jar 路径（覆盖自动探测）

.PARAMETER Keystore
  手动指定签名用 keystore 文件路径（覆盖自动探测 / 自动生成）

.EXAMPLE
  ./build.ps1
  ./build.ps1 -NdkPath D:\android-ndk-r26d
#>
param(
  [string]$NdkPath    = '',
  [string]$SdkRoot    = '',
  [string]$JdkHome    = '',
  [string]$AndroidJar = '',
  [string]$Keystore   = ''
)
$ErrorActionPreference = 'Stop'

# ── 0. 项目根目录（相对路径） ──────────────────────────────────────────
$PROJ = $PSScriptRoot
if (-not $PROJ) { $PROJ = (Get-Location).Path }
$build = Join-Path $PROJ 'build'

# ── 1. 探测 JDK（需要 Java 11+，d8 / apksigner 要求） ─────────────────
function Find-JdkHome {
  if ($JdkHome -and (Test-Path $JdkHome)) { return $JdkHome }

  # JAVA_HOME 环境变量
  if ($env:JAVA_HOME -and (Test-Path $env:JAVA_HOME)) { return $env:JAVA_HOME }

  # 从 PATH 中的 javac 推断
  $javacInPath = Get-Command javac -ErrorAction SilentlyContinue
  if ($javacInPath) {
    $candidate = Split-Path (Split-Path $javacInPath.Source)
    if (Test-Path (Join-Path $candidate 'bin/javac.exe')) { return $candidate }
  }

  # 常见安装路径
  foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, $env:LOCALAPPDATA)) {
    if (-not $base) { continue }
    $found = Get-ChildItem $base -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue |
             Sort-Object Name -Descending | Select-Object -First 1
    if ($found -and (Test-Path (Join-Path $found.FullName 'bin/javac.exe'))) {
      return $found.FullName
    }
  }

  throw "JDK not found. Install JDK 11+ and set JAVA_HOME, or pass -JdkHome."
}

$JDKHOME = Find-JdkHome
$JDK     = Join-Path $JDKHOME 'bin'
$env:JAVA_HOME = $JDKHOME
$env:Path = "$JDK;" + $env:Path

$javac = Join-Path $JDK 'javac.exe'
$jar   = Join-Path $JDK 'jar.exe'

# 验证 JDK 版本 >= 11
$jver = & $javac -version 2>&1 | ForEach-Object { $_.ToString() }
"JDK = $JDKHOME ($jver)"

# ── 2. 探测 Android SDK ──────────────────────────────────────────────
function Find-SdkRoot {
  if ($SdkRoot -and (Test-Path $SdkRoot)) { return $SdkRoot }
  if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) { return $env:ANDROID_HOME }
  if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) { return $env:ANDROID_SDK_ROOT }

  # 常见路径
  $candidates = @(
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    'C:\Android\Sdk',
    (Join-Path $env:HOME 'Android\Sdk'),
    (Join-Path $env:USERPROFILE 'Tools\androidsdk'),
    'C:\Android\Sdk',
    'D:\Android\Sdk'
  )
  foreach ($c in $candidates) {
    if ($c -and (Test-Path $c)) { return $c }
  }

  # 尝试从 PATH 中的 aapt2/adb 推断
  foreach ($tool in @('aapt2', 'adb')) {
    $found = Get-Command $tool -ErrorAction SilentlyContinue
    if ($found) {
      # aapt2 在 build-tools/xx/，往上 2 层就是 SDK root
      return Split-Path (Split-Path (Split-Path $found.Source))
    }
  }

  return $null
}

$sdk = Find-SdkRoot

# ── 3. 定位 Build-Tools（d8, aapt2, zipalign, apksigner） ───────────
# 支持标准 SDK 布局（build-tools/34.0.0/）和非标准布局（直接放子目录）
function Find-BuildTools {
  param([string]$SdkRoot)

  if (-not $SdkRoot) { return $null }

  # 标准布局: SDK_ROOT/build-tools/<version>/
  $btDir = Join-Path $SdkRoot 'build-tools'
  if (Test-Path $btDir) {
    $latest = Get-ChildItem $btDir -Directory | Sort-Object Name -Descending | Select-Object -First 1
    if ($latest -and (Test-Path (Join-Path $latest.FullName 'aapt2.exe'))) {
      return $latest.FullName
    }
  }

  # 非标准布局: SDK_ROOT 下直接搜索含 aapt2.exe 的子目录（最多 2 层）
  $found = Get-ChildItem $SdkRoot -Directory -Depth 1 -ErrorAction SilentlyContinue |
           Where-Object { Test-Path (Join-Path $_.FullName 'aapt2.exe') } |
           Sort-Object Name -Descending |
           Select-Object -First 1
  if ($found) { return $found.FullName }

  return $null
}

$bt = Find-BuildTools $sdk
if (-not $bt) {
  throw "Android SDK build-tools not found. Install SDK and set ANDROID_HOME, or pass -SdkRoot."
}
"SDK = $sdk"
"Build-Tools = $bt"

$d8        = Join-Path $bt 'd8.bat'
$aapt2     = Join-Path $bt 'aapt2.exe'
$zipalign  = Join-Path $bt 'zipalign.exe'
$apksigner = Join-Path $bt 'apksigner.bat'

# 验证关键工具存在
foreach ($t in @($d8, $aapt2, $zipalign, $apksigner)) {
  if (-not (Test-Path $t)) { throw "Required tool not found: $t" }
}

# ── 4. 探测 android.jar ─────────────────────────────────────────────
function Find-AndroidJar {
  if ($AndroidJar -and (Test-Path $AndroidJar)) { return $AndroidJar }

  if ($sdk) {
    # 标准布局: platforms/android-34/android.jar
    foreach ($platName in @('platforms', 'plat', 'Platform')) {
      $platDir = Join-Path $sdk $platName
      if (Test-Path $platDir) {
        $jar = Get-ChildItem $platDir -Directory -ErrorAction SilentlyContinue |
               Sort-Object Name -Descending |
               ForEach-Object { Join-Path $_.FullName 'android.jar' } |
               Where-Object { Test-Path $_ } |
               Select-Object -First 1
        if ($jar) { return $jar }
      }
    }

    # 非标准布局: 直接搜索 android.jar
    $jar = Get-ChildItem $sdk -Recurse -Filter 'android.jar' -ErrorAction SilentlyContinue |
           Select-Object -First 1 -ExpandProperty FullName
    if ($jar) { return $jar }
  }

  throw "android.jar not found. Install Android SDK platform, or pass -AndroidJar."
}

$AndroidJar = Find-AndroidJar
"android.jar = $AndroidJar"

# ── 5. 探测 Android NDK ─────────────────────────────────────────────
function Find-Ndk {
  if ($NdkPath -and (Test-Path $NdkPath)) { return $NdkPath }

  # 环境变量
  if ($env:ANDROID_NDK_HOME -and (Test-Path $env:ANDROID_NDK_HOME)) { return $env:ANDROID_NDK_HOME }
  if ($env:NDK_HOME -and (Test-Path $env:NDK_HOME)) { return $env:NDK_HOME }

  # SDK Manager 安装的 NDK
  if ($sdk) {
    $ndkBundle = Join-Path $sdk 'ndk'
    if (Test-Path $ndkBundle) {
      $found = Get-ChildItem $ndkBundle -Directory | Sort-Object Name -Descending | Select-Object -First 1
      if ($found) { return $found.FullName }
    }
  }

  # 在项目目录及其祖先目录中搜索
  $dir = $PROJ
  for ($i = 0; $i -lt 4; $i++) {
    $found = Get-ChildItem $dir -Directory -Filter 'android-ndk-*' -ErrorAction SilentlyContinue |
             Sort-Object Name -Descending | Select-Object -First 1
    if ($found) { return $found.FullName }
    $parent = Split-Path $dir
    if ($parent -eq $dir) { break }
    $dir = $parent
  }

  # 常见全局路径
  foreach ($base in @($env:LOCALAPPDATA, 'D:\', 'C:\')) {
    if (-not $base) { continue }
    $found = Get-ChildItem $base -Directory -Filter 'android-ndk-*' -ErrorAction SilentlyContinue |
             Sort-Object Name -Descending | Select-Object -First 1
    if ($found) { return $found.FullName }
  }

  Write-Warning "Android NDK not found. Will reuse libantirecall.so from existing feishu-antirecall.apk (Java-only build). Install NDK for full native rebuild."
  return $null
}

$NDK = Find-Ndk
$clangpp = $null
if ($NDK) {
  $LLVM = Join-Path $NDK 'toolchains\llvm\prebuilt\windows-x86_64\bin'
  foreach ($cand in @('clang++.cmd','clang++.exe','clang++')) {
    $p = Join-Path $LLVM $cand
    if (Test-Path $p) { $clangpp = $p; break }
  }
  if (-not $clangpp) { Write-Warning "clang++ not found under $LLVM; falling back to reuse-so mode." }
  else { "NDK = $NDK"; "clang++ = $clangpp" }
}

# ── 6. Keystore（签名用） ───────────────────────────────────────────
function Find-OrCreate-Keystore {
  if ($Keystore -and (Test-Path $Keystore)) { return $Keystore }

  # 项目根目录下的 debug.keystore
  $localKs = Join-Path $PROJ 'debug.keystore'
  if (Test-Path $localKs) { return $localKs }

  # 自动生成一个 debug keystore
  $keytool = Join-Path $JDK 'keytool.exe'
  if (-not (Test-Path $keytool)) { throw "keytool not found at $keytool" }

  Write-Host "Generating debug keystore at $localKs ..."
  & $keytool -genkeypair -v `
      -keystore $localKs `
      -storepass android `
      -alias androiddebugkey `
      -keypass android `
      -keyalg RSA `
      -keysize 2048 `
      -validity 10000 `
      -dname 'CN=Debug,O=Debug,C=US' 2>&1 | Out-Null

  if ($LASTEXITCODE) { throw "keytool failed" }
  if (Test-Path $localKs) { return $localKs }

  throw "Failed to create debug keystore. Pass -Keystore to specify one."
}

$KS = Find-OrCreate-Keystore
"Keystore = $KS"

# ── 构建开始 ──────────────────────────────────────────────────────────

if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force $build, "$build\stubs", "$build\app" | Out-Null

if ($clangpp) {
  Write-Host "`n== 0. NDK compile libantirecall.so (arm64-v8a) =="
  $njni = "$PROJ\native\jni"
  & $clangpp --target=aarch64-linux-android24 -fPIC -shared -O2 -fvisibility=hidden -fno-rtti -fno-exceptions -mno-outline-atomics -static-libstdc++ `
      '-Wl,-z,now' '-Wl,-z,relro' '-Wl,-z,noexecstack' '-Wl,--no-undefined' '-Wl,--pack-dyn-relocs=none' '-Wl,--hash-style=both' `
      -o "$build\libantirecall.so" "$njni\antirecall.cpp" "$njni\And64InlineHook.cpp" -llog -ldl -lm
  if ($LASTEXITCODE) { throw "ndk compile libantirecall failed" }
  # 额外编 libresign.so (离职统计用; 与 build.sh 一致)
  & $clangpp --target=aarch64-linux-android24 -fPIC -shared -O2 -fvisibility=hidden -fno-rtti -fno-exceptions -mno-outline-atomics -static-libstdc++ `
      '-Wl,-z,now' '-Wl,-z,relro' '-Wl,-z,noexecstack' '-Wl,--no-undefined' '-Wl,--pack-dyn-relocs=none' '-Wl,--hash-style=both' `
      -o "$build\libresign.so" "$njni\resign.cpp" "$njni\And64InlineHook.cpp" -llog -ldl -lm
  if ($LASTEXITCODE) { throw "ndk compile libresign failed" }
  "libresign.so = $([math]::Round((Get-Item "$build\libresign.so").Length/1KB,1))KB"
} else {
  Write-Host "`n== 0. (NDK 缺失) 复用发布版 APK 里的 libantirecall.so =="
  $releaseApk = Join-Path $PROJ 'feishu-antirecall.apk'
  if (-not (Test-Path $releaseApk)) { throw "NDK 未装且找不到 $releaseApk, 无法复用 so" }
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $src = [System.IO.Compression.ZipFile]::OpenRead($releaseApk)
  try {
    $e = $src.GetEntry('lib/arm64-v8a/libantirecall.so')
    if (-not $e) { throw "libantirecall.so not found in $releaseApk" }
    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, "$build\libantirecall.so", $true)
  } finally { $src.Dispose() }
}
"libantirecall.so = $([math]::Round((Get-Item "$build\libantirecall.so").Length/1KB,1))KB"

Write-Host "`n== 1. javac stubs =="
$stubFiles = Get-ChildItem "$PROJ\stubs" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac --release 8 -d "$build\stubs" $stubFiles
if ($LASTEXITCODE) { throw "javac stubs failed" }

Write-Host "`n== 2. javac module =="
$srcFiles = Get-ChildItem "$PROJ\app\src\main\java" -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $javac -g -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AndroidJar" -cp "$build\stubs" -d "$build\app" $srcFiles
if ($LASTEXITCODE) { throw "javac module failed" }

Write-Host "`n== 3. d8 -> classes.dex (no desugar) =="
$classFiles = Get-ChildItem "$build\app" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $d8 --min-api 22 --no-desugaring --output "$build" $classFiles
if ($LASTEXITCODE) { throw "d8 failed" }

Write-Host "`n== 4. aapt2 compile + link =="
& $aapt2 compile --dir "$PROJ\app\src\main\res" -o "$build\res.zip"
if ($LASTEXITCODE) { throw "aapt2 compile failed" }
& $aapt2 link -o "$build\app-unsigned.apk" -I "$AndroidJar" `
    --manifest "$PROJ\app\src\main\AndroidManifest.xml" `
    -A "$PROJ\app\src\main\assets" `
    --min-sdk-version 22 --target-sdk-version 34 `
    "$build\res.zip"
if ($LASTEXITCODE) { throw "aapt2 link failed" }

Write-Host "`n== 5. 塞入 classes.dex + lib/arm64-v8a/libantirecall.so =="
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open("$build\app-unsigned.apk", 'Update')
try {
  $old = $zip.GetEntry('classes.dex'); if ($old) { $old.Delete() }
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$build\classes.dex", 'classes.dex') | Out-Null
  $oldso = $zip.GetEntry('lib/arm64-v8a/libantirecall.so'); if ($oldso) { $oldso.Delete() }
  [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$build\libantirecall.so", 'lib/arm64-v8a/libantirecall.so') | Out-Null
  if (Test-Path "$build\libresign.so") {
    $oldso2 = $zip.GetEntry('lib/arm64-v8a/libresign.so'); if ($oldso2) { $oldso2.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, "$build\libresign.so", 'lib/arm64-v8a/libresign.so') | Out-Null
  }
} finally { $zip.Dispose() }

Write-Host "`n== 6. zipalign =="
& $zipalign -p -f 4 "$build\app-unsigned.apk" "$build\app-aligned.apk"
if ($LASTEXITCODE) { throw "zipalign failed" }

Write-Host "`n== 7. apksigner sign =="
& $apksigner sign --ks "$KS" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android --out "$PROJ\feishu-antirecall.apk" "$build\app-aligned.apk"
if ($LASTEXITCODE) { throw "apksigner failed" }

Write-Host "`n== DONE =="
& $apksigner verify --print-certs "$PROJ\feishu-antirecall.apk" 2>&1 | Select-Object -First 3
"APK = $PROJ\feishu-antirecall.apk  size=$([math]::Round((Get-Item "$PROJ\feishu-antirecall.apk").Length/1KB,1))KB"
