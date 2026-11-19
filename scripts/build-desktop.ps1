param(
    [switch]$Release
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $env:CARGO_TARGET_DIR) {
    $env:CARGO_TARGET_DIR = Join-Path $env:LOCALAPPDATA "ysp-live\cargo-target"
}

$args = @("build", "-p", "ysp-desktop")
if ($Release) { $args += "--release" }

Push-Location $root
try {
    cargo @args
    if ($LASTEXITCODE -ne 0) { throw "cargo build failed" }
    $profile = if ($Release) { "release" } else { "debug" }
    $binary = Join-Path $env:CARGO_TARGET_DIR "$profile\ysp-desktop.exe"
    if (Test-Path $binary) {
        Get-Item $binary | Select-Object FullName, Length
    }
} finally {
    Pop-Location
}

