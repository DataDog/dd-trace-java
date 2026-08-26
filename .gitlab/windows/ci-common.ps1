# Shared helpers for the Windows CI jobs in .gitlab/windows-tests.yml.
# Dot-source this file at the top of a job script: . .gitlab/windows/ci-common.ps1

$ErrorActionPreference = "Stop"

# Runs a native command with its stderr merged into stdout.
#
# PowerShell wraps a native command's stderr in ErrorRecord objects, and under
# $ErrorActionPreference = 'Stop' the first one becomes a terminating error.
# docker writes build progress, push progress and "manifest unknown" to stderr,
# so calling it directly would fail the job on paths that are expected to work.
# Callers must gate on $LASTEXITCODE, which this function leaves untouched.
#
# Arguments are passed as a single array rather than splatted, so tokens such as
# "--tag" reach the command instead of being bound as function parameters.
function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string] $Command,
        [string[]] $Arguments = @()
    )

    # Function-scoped, so the caller keeps 'Stop' for cmdlet errors.
    $ErrorActionPreference = "Continue"
    & $Command @Arguments 2>&1 | ForEach-Object { Write-Host "$_" }
}

# Resolves the mutable image tag used by this personal prototype.
function Get-WindowsCiImage {
    if ([string]::IsNullOrWhiteSpace($env:WINDOWS_BUILD_IMAGE)) {
        throw "WINDOWS_BUILD_IMAGE is not set"
    }
    return $env:WINDOWS_BUILD_IMAGE
}
