# setup.ps1 — Clones upstream Velocity-CTD, applies Conduit overlays, and prepares the build tree.
# Run this once before building on Windows: .\scripts\setup.ps1
# Requires: Git, Java 21+

param([switch]$CI)

$ErrorActionPreference = "Stop"

$ScriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$RootDir    = Split-Path -Parent $ScriptDir
$UpstreamRepo   = "https://github.com/GemstoneGG/Velocity-CTD.git"
$UpstreamBranch = "dev"
$UpstreamDir    = Join-Path $RootDir ".upstream-velocity"

Write-Host "==> Conduit setup"
Write-Host "    Root:     $RootDir"
Write-Host "    Upstream: $UpstreamRepo @ $UpstreamBranch"
Write-Host ""

# ── 1. Clone or update upstream ──────────────────────────────────────────────
if (Test-Path (Join-Path $UpstreamDir ".git")) {
    Write-Host "==> Updating cached upstream clone..."
    git -C $UpstreamDir remote set-url origin $UpstreamRepo
    git -C $UpstreamDir fetch --depth=1 origin $UpstreamBranch
    git -C $UpstreamDir checkout FETCH_HEAD
} else {
    Write-Host "==> Cloning upstream Velocity-CTD (depth=1)..."
    git clone --depth=1 --branch $UpstreamBranch $UpstreamRepo $UpstreamDir
}

# ── Helper: mirror a directory, deleting files not in source ─────────────────
# Equivalent to: rsync -a --delete --exclude='**/X.java' src/ dst/
function Mirror-Module {
    param(
        [string]$Source,
        [string]$Dest,
        [string[]]$ExcludeFileNames = @()
    )

    if (-not (Test-Path $Dest)) {
        New-Item -ItemType Directory -Path $Dest | Out-Null
    }

    # robocopy /MIR mirrors and deletes extras; /XF excludes by filename (any depth)
    $robocopyArgs = @($Source, $Dest, "/MIR", "/E", "/NJH", "/NJS")
    if ($ExcludeFileNames.Count -gt 0) {
        $robocopyArgs += "/XF"
        $robocopyArgs += $ExcludeFileNames
    }
    # robocopy exit codes 0-7 are success (bit field of what was copied/skipped)
    $result = & robocopy @robocopyArgs
    if ($LASTEXITCODE -ge 8) {
        Write-Error "robocopy failed with exit code $LASTEXITCODE"
    }
}

# ── 2. Copy upstream source into our project tree ────────────────────────────
Write-Host "==> Syncing upstream source files..."

$excludeFiles = @(
    "KnownPacksPacket.java",
    "ConnectionManager.java",
    "VelocityServer.java",
    "ServerChannelInitializer.java",
    "HandshakeSessionHandler.java"
)

foreach ($module in @("api", "native", "proxy", "luckperms-integration", "build-logic", "config")) {
    $src = Join-Path $UpstreamDir $module
    $dst = Join-Path $RootDir $module
    if (Test-Path $src) {
        Mirror-Module -Source $src -Dest $dst -ExcludeFileNames $excludeFiles
    }
}

# Copy upstream-owned Gradle support files into the generated working tree.
foreach ($f in @("gradlew", "gradlew.bat", "gradle", "HEADER.txt", "HEADER-CTD.txt")) {
    $srcPath = Join-Path $UpstreamDir $f
    $dstPath = Join-Path $RootDir $f
    if (Test-Path $srcPath) {
        Remove-Item -Recurse -Force $dstPath -ErrorAction SilentlyContinue
        Copy-Item -Recurse $srcPath $dstPath
    }
}

# ── 3. Apply Conduit overlays (modified upstream files) ──────────────────────
Write-Host "==> Applying overlays (modified upstream files)..."
$overlaysDir = Join-Path $RootDir "overlays"
if (Test-Path $overlaysDir) {
    Copy-Item -Path "$overlaysDir\*" -Destination $RootDir -Recurse -Force
    Write-Host "    Overlays applied."
}

# ── 4. Apply Conduit additions (new files) ───────────────────────────────────
Write-Host "==> Applying additions (new Conduit files)..."
$additionsDir = Join-Path $RootDir "additions"
if (Test-Path $additionsDir) {
    Copy-Item -Path "$additionsDir\*" -Destination $RootDir -Recurse -Force
    Write-Host "    Additions applied."
}

# ── 5. Write the Conduit build metadata ──────────────────────────────────────
$conduitVersion = (Get-Content (Join-Path $RootDir "gradle.properties") |
    Where-Object { $_ -match "^conduit\.version=" }) -replace "^conduit\.version=", ""
$buildTime = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$gitHash   = (git -C $RootDir rev-parse --short HEAD 2>$null)
if ([string]::IsNullOrWhiteSpace($gitHash)) { $gitHash = "unknown" }

$resourceDir = Join-Path $RootDir "proxy\src\main\resources\com\velocitypowered\proxy\conduit"
New-Item -ItemType Directory -Path $resourceDir -Force | Out-Null

@"
conduit.version=$conduitVersion
conduit.build.time=$buildTime
conduit.git.hash=$gitHash
conduit.upstream.branch=$UpstreamBranch
"@ | Set-Content (Join-Path $resourceDir "conduit-build.properties") -Encoding UTF8

Write-Host "==> Setup complete. Run '.\gradlew.bat build' to compile."
