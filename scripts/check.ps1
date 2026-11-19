$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $env:CARGO_TARGET_DIR) {
    $env:CARGO_TARGET_DIR = Join-Path $env:LOCALAPPDATA "ysp-live\cargo-target"
}

Push-Location $root
try {
    cargo test -p ysp-core
    if ($LASTEXITCODE -ne 0) { throw "core tests failed" }
    cargo check -p ysp-desktop
    if ($LASTEXITCODE -ne 0) { throw "desktop check failed" }
    cargo check -p ysp-android-jni --target aarch64-linux-android
    if ($LASTEXITCODE -ne 0) { throw "android jni check failed" }
    Write-Host "All Rust checks passed."
} finally {
    Pop-Location
}

