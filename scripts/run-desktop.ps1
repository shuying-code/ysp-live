param(
    [string]$Channel = "CCTV1",
    [switch]$Visible,
    [int]$RunSeconds = 0
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
if (-not $env:CARGO_TARGET_DIR) {
    $env:CARGO_TARGET_DIR = Join-Path $env:LOCALAPPDATA "ysp-live\cargo-target"
}

$args = @("run", "-p", "ysp-desktop", "--", "--channel", $Channel)
if ($Visible) { $args += "--debug-visible" }
if ($RunSeconds -gt 0) { $args += @("--run-seconds", "$RunSeconds") }

Push-Location $root
try {
    cargo @args
} finally {
    Pop-Location
}

