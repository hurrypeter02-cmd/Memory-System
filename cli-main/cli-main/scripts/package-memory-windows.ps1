[CmdletBinding()]
param(
    [string]$OutputRoot = "",
    [switch]$Clean
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-OutputRoot {
    param(
        [string]$InputOutputRoot,
        [string]$RepoRoot
    )

    if ([string]::IsNullOrWhiteSpace($InputOutputRoot)) {
        return [System.IO.Path]::GetFullPath((Join-Path $RepoRoot "dist"))
    }

    if ([System.IO.Path]::IsPathRooted($InputOutputRoot)) {
        return [System.IO.Path]::GetFullPath($InputOutputRoot)
    }

    return [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $InputOutputRoot))
}

function Assert-SafePackageDelete {
    param(
        [string]$OutputRoot,
        [string]$PackageDir
    )

    $resolvedOutputRoot = [System.IO.Path]::GetFullPath($OutputRoot).TrimEnd("\", "/")
    $resolvedPackageDir = [System.IO.Path]::GetFullPath($PackageDir).TrimEnd("\", "/")
    $expectedPrefix = $resolvedOutputRoot + [System.IO.Path]::DirectorySeparatorChar

    if (-not $resolvedPackageDir.StartsWith($expectedPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean package outside output root: $resolvedPackageDir"
    }

    if ((Split-Path -Leaf $resolvedPackageDir) -ne "lark-cli-memory-windows") {
        throw "Refusing to clean unexpected package directory: $resolvedPackageDir"
    }
}

function Write-Utf8File {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Content
    )

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$outputRootPath = Resolve-OutputRoot -InputOutputRoot $OutputRoot -RepoRoot $repoRoot
$packageDir = Join-Path $outputRootPath "lark-cli-memory-windows"
$examplesDir = Join-Path $packageDir "examples"
$exePath = Join-Path $packageDir "lark-cli.exe"

if ($Clean -and (Test-Path -LiteralPath $packageDir)) {
    Assert-SafePackageDelete -OutputRoot $outputRootPath -PackageDir $packageDir
    Remove-Item -LiteralPath $packageDir -Recurse -Force
}

New-Item -ItemType Directory -Path $packageDir -Force | Out-Null
New-Item -ItemType Directory -Path $examplesDir -Force | Out-Null

Push-Location $repoRoot
try {
    Write-Host "Building lark-cli.exe..."
    & go build -trimpath -o $exePath .
    if ($LASTEXITCODE -ne 0) {
        throw "go build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

$verifySource = Join-Path $PSScriptRoot "verify-memory-windows.ps1"
if (-not (Test-Path -LiteralPath $verifySource)) {
    throw "Missing verify script: $verifySource"
}
Copy-Item -LiteralPath $verifySource -Destination (Join-Path $packageDir "verify.ps1") -Force

$templatesDir = Join-Path $PSScriptRoot "memory-windows"
$readmeSource = Join-Path $templatesDir "README.zh-CN.md"
$reviewSource = Join-Path $templatesDir "execution-review.zh-CN.md"
if (-not (Test-Path -LiteralPath $readmeSource)) {
    throw "Missing README template: $readmeSource"
}
if (-not (Test-Path -LiteralPath $reviewSource)) {
    throw "Missing review template: $reviewSource"
}
Copy-Item -LiteralPath $readmeSource -Destination (Join-Path $packageDir "README.md") -Force
Copy-Item -LiteralPath $reviewSource -Destination (Join-Path $packageDir "execution-review.zh-CN.md") -Force

$contract = @'
# Memory process contract

Memory integrates with Feishu by spawning `lark-cli.exe`. It does not import the
Go packages directly and does not manage Feishu credentials.

## Request shape

Build an argument array instead of a single shell string.

Example:

```text
lark-cli.exe docs +search --query memory --dry-run --as user
```

For commands that support JSON input, pass compact JSON through documented CLI
flags such as `--params` or `--data`.

## Response handling

- Treat stdout as the success data channel.
- Treat stderr as diagnostics or an error envelope.
- Check the process exit code before parsing business data.
- Redact secrets and tenant document content from logs.

## Credential boundary

Memory must not store app secrets, access tokens, refresh tokens, or keychain
material. Operators complete login with:

```powershell
.\lark-cli.exe config init --new
.\lark-cli.exe auth login --recommend
.\lark-cli.exe auth status
```
'@
Write-Utf8File -Path (Join-Path $examplesDir "memory-process-contract.md") -Content $contract

$docsExample = @'
[CmdletBinding()]
param(
    [string]$ExePath = "",
    [string]$Query = "memory"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ExePath)) {
    $scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $PSCommandPath }
    $ExePath = Join-Path $scriptDir "..\lark-cli.exe"
}

$oldAppId = $env:LARKSUITE_CLI_APP_ID
$oldAppSecret = $env:LARKSUITE_CLI_APP_SECRET
$oldBrand = $env:LARKSUITE_CLI_BRAND
$oldConfigDir = $env:LARKSUITE_CLI_CONFIG_DIR
$tempConfigDir = Join-Path ([System.IO.Path]::GetTempPath()) ("lark-cli-memory-example-" + [System.Guid]::NewGuid().ToString("N"))
$exitCode = 0
try {
    New-Item -ItemType Directory -Path $tempConfigDir -Force | Out-Null
    if (-not $env:LARKSUITE_CLI_APP_ID) { $env:LARKSUITE_CLI_APP_ID = "memory_example_app" }
    if (-not $env:LARKSUITE_CLI_APP_SECRET) { $env:LARKSUITE_CLI_APP_SECRET = "memory_example_secret" }
    if (-not $env:LARKSUITE_CLI_BRAND) { $env:LARKSUITE_CLI_BRAND = "feishu" }
    $env:LARKSUITE_CLI_CONFIG_DIR = $tempConfigDir

    $arguments = @("docs", "+search", "--query", $Query, "--dry-run", "--as", "user")
    & $ExePath @arguments
    $exitCode = $LASTEXITCODE
} finally {
    if ($null -eq $oldAppId) { Remove-Item Env:LARKSUITE_CLI_APP_ID -ErrorAction SilentlyContinue } else { $env:LARKSUITE_CLI_APP_ID = $oldAppId }
    if ($null -eq $oldAppSecret) { Remove-Item Env:LARKSUITE_CLI_APP_SECRET -ErrorAction SilentlyContinue } else { $env:LARKSUITE_CLI_APP_SECRET = $oldAppSecret }
    if ($null -eq $oldBrand) { Remove-Item Env:LARKSUITE_CLI_BRAND -ErrorAction SilentlyContinue } else { $env:LARKSUITE_CLI_BRAND = $oldBrand }
    if ($null -eq $oldConfigDir) { Remove-Item Env:LARKSUITE_CLI_CONFIG_DIR -ErrorAction SilentlyContinue } else { $env:LARKSUITE_CLI_CONFIG_DIR = $oldConfigDir }
    $resolvedTemp = [System.IO.Path]::GetFullPath($tempConfigDir)
    $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($resolvedTemp.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedTemp)) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
    }
}
exit $exitCode
'@
Write-Utf8File -Path (Join-Path $examplesDir "docs-search-dry-run.ps1") -Content $docsExample

$driveExample = @'
[CmdletBinding()]
param(
    [string]$ExePath = "",
    [string]$Query = "memory"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ExePath)) {
    $scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $PSCommandPath }
    $ExePath = Join-Path $scriptDir "..\lark-cli.exe"
}

$oldAppId = $env:LARKSUITE_CLI_APP_ID
$oldAppSecret = $env:LARKSUITE_CLI_APP_SECRET
$oldBrand = $env:LARKSUITE_CLI_BRAND
$oldConfigDir = $env:LARKSUITE_CLI_CONFIG_DIR
$tempConfigDir = Join-Path ([System.IO.Path]::GetTempPath()) ("lark-cli-memory-example-" + [System.Guid]::NewGuid().ToString("N"))
$exitCode = 0
try {
    New-Item -ItemType Directory -Path $tempConfigDir -Force | Out-Null
    if (-not $env:LARKSUITE_CLI_APP_ID) { $env:LARKSUITE_CLI_APP_ID = "memory_example_app" }
    if (-not $env:LARKSUITE_CLI_APP_SECRET) { $env:LARKSUITE_CLI_APP_SECRET = "memory_example_secret" }
    if (-not $env:LARKSUITE_CLI_BRAND) { $env:LARKSUITE_CLI_BRAND = "feishu" }
    $env:LARKSUITE_CLI_CONFIG_DIR = $tempConfigDir

    $arguments = @("drive", "+search", "--query", $Query, "--dry-run", "--as", "user")
    & $ExePath @arguments
    $exitCode = $LASTEXITCODE
} finally {
    if ($null -eq $oldAppId) { Remove-Item Env:LARKSUITE_CLI_APP_ID -ErrorAction SilentlyContinue } else { $env:LARKSUITE_CLI_APP_ID = $oldAppId }
    if ($null -eq $oldAppSecret) { Remove-Item Env:LARKSUITE_CLI_APP_SECRET -ErrorAction SilentlyContinue } else { $env:LARKSUITE_CLI_APP_SECRET = $oldAppSecret }
    if ($null -eq $oldBrand) { Remove-Item Env:LARKSUITE_CLI_BRAND -ErrorAction SilentlyContinue } else { $env:LARKSUITE_CLI_BRAND = $oldBrand }
    if ($null -eq $oldConfigDir) { Remove-Item Env:LARKSUITE_CLI_CONFIG_DIR -ErrorAction SilentlyContinue } else { $env:LARKSUITE_CLI_CONFIG_DIR = $oldConfigDir }
    $resolvedTemp = [System.IO.Path]::GetFullPath($tempConfigDir)
    $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($resolvedTemp.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedTemp)) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
    }
}
exit $exitCode
'@
Write-Utf8File -Path (Join-Path $examplesDir "drive-search-dry-run.ps1") -Content $driveExample

$wikiExample = @'
[CmdletBinding()]
param(
    [string]$ExePath = "",
    [string]$Title = "Memory"
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ExePath)) {
    $scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $PSCommandPath }
    $ExePath = Join-Path $scriptDir "..\lark-cli.exe"
}

$oldAppId = $env:LARKSUITE_CLI_APP_ID
$oldAppSecret = $env:LARKSUITE_CLI_APP_SECRET
$oldBrand = $env:LARKSUITE_CLI_BRAND
$oldConfigDir = $env:LARKSUITE_CLI_CONFIG_DIR
$tempConfigDir = Join-Path ([System.IO.Path]::GetTempPath()) ("lark-cli-memory-example-" + [System.Guid]::NewGuid().ToString("N"))
$exitCode = 0
try {
    New-Item -ItemType Directory -Path $tempConfigDir -Force | Out-Null
    if (-not $env:LARKSUITE_CLI_APP_ID) { $env:LARKSUITE_CLI_APP_ID = "memory_example_app" }
    if (-not $env:LARKSUITE_CLI_APP_SECRET) { $env:LARKSUITE_CLI_APP_SECRET = "memory_example_secret" }
    if (-not $env:LARKSUITE_CLI_BRAND) { $env:LARKSUITE_CLI_BRAND = "feishu" }
    $env:LARKSUITE_CLI_CONFIG_DIR = $tempConfigDir

    $arguments = @("wiki", "+node-create", "--title", $Title, "--dry-run", "--as", "user")
    & $ExePath @arguments
    $exitCode = $LASTEXITCODE
} finally {
    if ($null -eq $oldAppId) { Remove-Item Env:LARKSUITE_CLI_APP_ID -ErrorAction SilentlyContinue } else { $env:LARKSUITE_CLI_APP_ID = $oldAppId }
    if ($null -eq $oldAppSecret) { Remove-Item Env:LARKSUITE_CLI_APP_SECRET -ErrorAction SilentlyContinue } else { $env:LARKSUITE_CLI_APP_SECRET = $oldAppSecret }
    if ($null -eq $oldBrand) { Remove-Item Env:LARKSUITE_CLI_BRAND -ErrorAction SilentlyContinue } else { $env:LARKSUITE_CLI_BRAND = $oldBrand }
    if ($null -eq $oldConfigDir) { Remove-Item Env:LARKSUITE_CLI_CONFIG_DIR -ErrorAction SilentlyContinue } else { $env:LARKSUITE_CLI_CONFIG_DIR = $oldConfigDir }
    $resolvedTemp = [System.IO.Path]::GetFullPath($tempConfigDir)
    $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($resolvedTemp.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedTemp)) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
    }
}
exit $exitCode
'@
Write-Utf8File -Path (Join-Path $examplesDir "wiki-node-create-dry-run.ps1") -Content $wikiExample

Write-Host "Created package: $packageDir"
Write-Host "Run verification:"
Write-Host "  powershell -NoProfile -ExecutionPolicy Bypass -File `"$packageDir\verify.ps1`""
