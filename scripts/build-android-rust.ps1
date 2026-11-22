param(
    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86_64", "all")]
    [string]$Abi = "all"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { "C:\Android\Sdk" }
$ndk = if ($env:ANDROID_NDK_HOME) { $env:ANDROID_NDK_HOME } else { Join-Path $sdk "ndk\26.1.10909125" }
$isWindowsHost = $env:OS -eq "Windows_NT"
$hostTag = if ($isWindowsHost) {
    "windows-x86_64"
} elseif ($IsMacOS) {
    "darwin-x86_64"
} else {
    "linux-x86_64"
}
$toolchain = Join-Path $ndk "toolchains/llvm/prebuilt/$hostTag/bin"
$api = "23"

if (-not (Test-Path $toolchain)) {
    throw "Android NDK toolchain not found: $toolchain"
}

if (-not $env:CARGO_TARGET_DIR) {
    $env:CARGO_TARGET_DIR = Join-Path $env:LOCALAPPDATA "ysp-live\cargo-target"
}

$targets = @{
    "arm64-v8a" = @{
        Target = "aarch64-linux-android"
        Cc = "aarch64-linux-android$api-clang"
        Env = "aarch64_linux_android"
    }
    "armeabi-v7a" = @{
        Target = "armv7-linux-androideabi"
        Cc = "armv7a-linux-androideabi$api-clang"
        Env = "armv7_linux_androideabi"
    }
    "x86_64" = @{
        Target = "x86_64-linux-android"
        Cc = "x86_64-linux-android$api-clang"
        Env = "x86_64_linux_android"
    }
}

$selected = if ($Abi -eq "all") { $targets.Keys } else { @($Abi) }
foreach ($name in $selected) {
    $spec = $targets[$name]
    $compilerName = if ($isWindowsHost) { "$($spec.Cc).cmd" } else { $spec.Cc }
    $compiler = Join-Path $toolchain $compilerName
    $archiverName = if ($isWindowsHost) { "llvm-ar.exe" } else { "llvm-ar" }
    $archiver = Join-Path $toolchain $archiverName
    if (-not (Test-Path $compiler)) {
        throw "Compiler not found: $compiler"
    }

    $envKey = $spec.Target.ToUpperInvariant().Replace("-", "_")
    # cc-rs accepts both the Cargo style variable and the toolchain style
    # variable. Set both so dependencies with C build scripts work too.
    Set-Item -Path "Env:CC_$($spec.Env)" -Value $compiler
    Set-Item -Path "Env:CC_$($spec.Target)" -Value $compiler
    Set-Item -Path "Env:AR_$($spec.Env)" -Value $archiver
    Set-Item -Path "Env:AR_$($spec.Target)" -Value $archiver
    Set-Item -Path "Env:CARGO_TARGET_$envKey`_LINKER" -Value $compiler

    Write-Host "Building Rust JNI for $name ($($spec.Target))"
    cargo build --manifest-path (Join-Path $root "Cargo.toml") -p ysp-android-jni --release --target $($spec.Target)
    if ($LASTEXITCODE -ne 0) {
        throw "cargo build failed for $name"
    }

    $source = Join-Path $env:CARGO_TARGET_DIR "$($spec.Target)\release\libysp_core_android.so"
    $dest = Join-Path $root "android\app\src\main\jniLibs\$name"
    if (-not (Test-Path $source)) {
        throw "Rust shared library not found: $source"
    }
    New-Item -ItemType Directory -Force -Path $dest | Out-Null
    Copy-Item -Force -LiteralPath $source -Destination (Join-Path $dest "libysp_core_android.so")
    Get-Item (Join-Path $dest "libysp_core_android.so") | Select-Object FullName, Length
}

