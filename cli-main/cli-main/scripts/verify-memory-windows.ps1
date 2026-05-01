[CmdletBinding()]
param(
    [string]$PackageDir = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-PackageDir {
    param([string]$InputPackageDir)

    if (-not [string]::IsNullOrWhiteSpace($InputPackageDir)) {
        return [System.IO.Path]::GetFullPath($InputPackageDir)
    }

    $localExe = Join-Path $PSScriptRoot "lark-cli.exe"
    if (Test-Path -LiteralPath $localExe) {
        return [System.IO.Path]::GetFullPath($PSScriptRoot)
    }

    $repoDistPackage = Join-Path (Join-Path $PSScriptRoot "..") "dist\lark-cli-memory-windows"
    return [System.IO.Path]::GetFullPath($repoDistPackage)
}

function Invoke-CheckedCommand {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$ExePath,
        [Parameter(Mandatory = $true)][string[]]$Arguments,
        [string[]]$ExpectedPatterns = @()
    )

    Write-Host "==> $Name"
    $previousErrorActionPreference = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $output = & $ExePath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)

    if ($exitCode -ne 0) {
        Write-Host $text
        throw "$Name failed with exit code $exitCode"
    }

    foreach ($pattern in $ExpectedPatterns) {
        if ($text -notmatch $pattern) {
            Write-Host $text
            throw "$Name output did not match /$pattern/"
        }
    }

    Write-Host "[OK] $Name"
    return $text
}

function Save-Env {
    return @{
        LARKSUITE_CLI_APP_ID = $env:LARKSUITE_CLI_APP_ID
        LARKSUITE_CLI_APP_SECRET = $env:LARKSUITE_CLI_APP_SECRET
        LARKSUITE_CLI_BRAND = $env:LARKSUITE_CLI_BRAND
        LARKSUITE_CLI_CONFIG_DIR = $env:LARKSUITE_CLI_CONFIG_DIR
    }
}

function Restore-Env {
    param([hashtable]$Values)

    foreach ($name in $Values.Keys) {
        if ($null -eq $Values[$name]) {
            Remove-Item -Path "Env:$name" -ErrorAction SilentlyContinue
        } else {
            Set-Item -Path "Env:$name" -Value $Values[$name]
        }
    }
}

$resolvedPackageDir = Resolve-PackageDir -InputPackageDir $PackageDir
$exePath = Join-Path $resolvedPackageDir "lark-cli.exe"

if (-not (Test-Path -LiteralPath $exePath)) {
    throw "Missing lark-cli.exe at $exePath"
}

Write-Host "Verifying package: $resolvedPackageDir"

$null = Invoke-CheckedCommand `
    -Name "lark-cli --help" `
    -ExePath $exePath `
    -Arguments @("--help") `
    -ExpectedPatterns @("Usage|Available Commands|lark-cli")

$null = Invoke-CheckedCommand `
    -Name "lark-cli schema --help" `
    -ExePath $exePath `
    -Arguments @("schema", "--help") `
    -ExpectedPatterns @("schema --help|schema")

$savedEnv = Save-Env
$tempConfigDir = Join-Path ([System.IO.Path]::GetTempPath()) ("lark-cli-memory-verify-" + [System.Guid]::NewGuid().ToString("N"))
try {
    New-Item -ItemType Directory -Path $tempConfigDir -Force | Out-Null
    $env:LARKSUITE_CLI_APP_ID = "memory_verify_app"
    $env:LARKSUITE_CLI_APP_SECRET = "memory_verify_secret"
    $env:LARKSUITE_CLI_BRAND = "feishu"
    $env:LARKSUITE_CLI_CONFIG_DIR = $tempConfigDir

    $null = Invoke-CheckedCommand `
        -Name "docs search dry-run" `
        -ExePath $exePath `
        -Arguments @("docs", "+search", "--query", "memory", "--dry-run", "--as", "user") `
        -ExpectedPatterns @("doc_wiki/search|search/v2/doc_wiki")

    $null = Invoke-CheckedCommand `
        -Name "drive search dry-run" `
        -ExePath $exePath `
        -Arguments @("drive", "+search", "--query", "memory", "--dry-run", "--as", "user") `
        -ExpectedPatterns @("doc_wiki/search|search/v2/doc_wiki")

    $null = Invoke-CheckedCommand `
        -Name "wiki node-create dry-run" `
        -ExePath $exePath `
        -Arguments @("wiki", "+node-create", "--title", "Memory", "--dry-run", "--as", "user") `
        -ExpectedPatterns @("wiki|spaces|node|Create wiki node")
} finally {
    Restore-Env -Values $savedEnv
    $resolvedTemp = [System.IO.Path]::GetFullPath($tempConfigDir)
    $resolvedTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    if ($resolvedTemp.StartsWith($resolvedTempRoot, [System.StringComparison]::OrdinalIgnoreCase) -and (Test-Path -LiteralPath $resolvedTemp)) {
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
    }
}

Write-Host ""
Write-Host "Manual login validation, not executed by this script:"
Write-Host "  .\lark-cli.exe config init --new"
Write-Host "  .\lark-cli.exe auth login --recommend"
Write-Host "  .\lark-cli.exe auth status"
Write-Host ""
Write-Host "[OK] Package verification completed without real API calls."
