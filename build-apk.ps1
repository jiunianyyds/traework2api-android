# Build a debug APK and copy it to out\ with the version in the name.
#
# Usage:  .\build-apk.ps1
# Output: out\TraeWork2API-<versionName>-debug.apk
#
# NOTE: this file is deliberately ASCII-only. Windows PowerShell 5.1 reads .ps1 files
# as GBK, so UTF-8 Chinese comments here get mangled into bytes that break string
# quoting (verified the hard way). Chinese docs live in docs\ and CHANGELOG.md instead.
#
# The version has exactly ONE source: versionName in app\build.gradle.kts.
# Do not hardcode it here -- two places to sync means they drift.
# Offline build (--offline); the only dependency is okhttp 4.12.0, already cached.

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

# 1. Read the version (single source of truth)
$gradleFile = Join-Path $root "app\build.gradle.kts"
$m = [regex]::Match((Get-Content $gradleFile -Raw), 'versionName\s*=\s*"([^"]+)"')
if (-not $m.Success) { throw "versionName not found in $gradleFile" }
$version = $m.Groups[1].Value

# 2. Build
Write-Host "Building (versionName=$version, offline)..." -ForegroundColor Cyan
& (Join-Path $root "gradlew.bat") assembleDebug --offline
if ($LASTEXITCODE -ne 0) { throw "Build failed (exit=$LASTEXITCODE)" }

# 3. Copy to out\. Delete any old file first: a locked APK makes Copy-Item fail silently.
$src = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $src)) { throw "Build output missing: $src" }
$outDir = Join-Path $root "out"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$dst = Join-Path $outDir "TraeWork2API-$version-debug.apk"
Remove-Item $dst -ErrorAction SilentlyContinue
Copy-Item $src $dst

$sizeMb = [math]::Round((Get-Item $dst).Length / 1MB, 1)
Write-Host "Done: $dst ($sizeMb MB)" -ForegroundColor Green
