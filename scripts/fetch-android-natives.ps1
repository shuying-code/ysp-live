param(
    [ValidateSet("arm64-v8a", "armeabi-v7a", "x86_64", "x86")]
    [string]$Abi = "arm64-v8a",
    [switch]$Force
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$destination = Join-Path $root "android\app\src\main\jniLibs\$Abi"
$assetVersion = "v0.3.0"
$assetBase = "https://github.com/yuroyami/libmpv-kotlin-multiplatform/releases/download/$assetVersion"
$expectedSha256 = @{
    "arm64-v8a"   = "eb39891cf973fced3d84a418fa6ac8c95104add122a9e7048bcdf4b27cf01b12"
    "armeabi-v7a" = "2ddb60f137142c28b7f183574b6bec986e412567509373d2b5ee7d2edac8a927"
    "x86_64"      = "0ae4ddc84dbe4e37992e27440b8411458fa4a986780bd93456fd96a6a2f26ce9"
    "x86"         = "16578a130cf771730505505af5cf3ee51a51738a630e7e79e4335fd8616da146"
}

$archiveName = "libmpvkt-natives-$Abi.zip"
$archiveUrl = "$assetBase/$archiveName"
$expected = $expectedSha256[$Abi]

if (-not (Test-Path -LiteralPath $destination) -or $Force) {
    New-Item -ItemType Directory -Force -Path $destination | Out-Null
} elseif ((Get-ChildItem -LiteralPath $destination -Filter "*.so" -File | Measure-Object).Count -gt 0) {
    Write-Host "Native libs already present for $Abi. Use -Force to replace them."
    exit 0
}

$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("ysp-live-native-" + [guid]::NewGuid().ToString("N"))
$archivePath = Join-Path $tempRoot $archiveName
$extractPath = Join-Path $tempRoot "extract"

try {
    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
    Write-Host "Downloading $archiveUrl"

    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if ($curl) {
        & $curl.Source -L --retry 3 --retry-delay 2 -o $archivePath $archiveUrl
        if ($LASTEXITCODE -ne 0) {
            throw "curl failed with exit code $LASTEXITCODE"
        }
    } else {
        Invoke-WebRequest -Uri $archiveUrl -OutFile $archivePath
    }

    $actual = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        throw "SHA-256 mismatch for $Abi. Expected $expected, got $actual"
    }

    Expand-Archive -LiteralPath $archivePath -DestinationPath $extractPath -Force
    $libs = Get-ChildItem -LiteralPath $extractPath -Recurse -File -Filter "*.so"
    if ($libs.Count -eq 0) {
        throw "No .so files found in $archiveName"
    }

    if ($Force) {
        Get-ChildItem -LiteralPath $destination -File -Filter "*.so" |
            Remove-Item -Force
    }
    foreach ($lib in $libs) {
        Copy-Item -LiteralPath $lib.FullName -Destination $destination -Force
    }
    Write-Host "Installed $($libs.Count) native libs into $destination"
} finally {
    # This path is generated from GetTempPath() above and is never a repository path.
    if (Test-Path -LiteralPath $tempRoot) {
        Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}
