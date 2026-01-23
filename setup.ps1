<#
  .DESCRIPTION
    This script checks the development environment for required tools and configurations.
#>

# Enable error handling
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Check for required JDKs
function Get-JavaMajorVersion {
  param ($javaCommand)

  try {
    $ErrorActionPreference = 'Continue'
    $javaVersionOutput = & $javaCommand -version 2>&1
  }
  catch {
    return $null
  }
  finally {
    $ErrorActionPreference = 'Stop'
  }

  # Extract version from output like 'version "21.0.1"' or 'version "1.8.0_392"'
  if ($javaVersionOutput[0] -match 'version "1\.(\d+)') {
    # Old versioning scheme (Java 8 and earlier): 1.X.Y_Z -> major version is X
    return [int]$matches[1]
  }
  elseif ($javaVersionOutput[0] -match 'version "(\d+)') {
    # New versioning scheme (Java 9+): X.Y.Z -> major version is X
    return [int]$matches[1]
  }

  return $null
}

function Test-Jdk {
  param ($javaCommand, $minJavaVersion)

  $javaVersion = Get-JavaMajorVersion $javaCommand

  if ($null -eq $javaVersion) {
    Write-Host "❌ Could not determine Java version from $javaCommand." -ForegroundColor Red
    exit 1
  }
  elseif ($javaVersion -lt $minJavaVersion) {
    Write-Host "🟨 $javaCommand refers to JDK $javaVersion but JDK $minJavaVersion or above is recommended."
  }
  else {
    Write-Host "✅ $javaCommand is set to JDK $javaVersion."
  }
}

function Show-AvailableJdks {
  try {
    $ErrorActionPreference = 'Continue'
    $javaToolchainsOutput = & .\gradlew.bat -q javaToolchains 2>&1

    $jdkName = ''
    foreach ($line in $javaToolchainsOutput) {
      if ($line -match '^ \+ (.+)$') {
        $jdkName = $matches[1]
      }
      elseif ($line -match '^\s+\| Location:\s+(.+)$') {
        if ($jdkName) {
          Write-Host "✅ $jdkName from $($matches[1])."
          $jdkName = ''
        }
      }
    }
  }
  catch {
    Write-Host "⚠️ Could not retrieve available JDKs from Gradle."
  }
  finally {
    $ErrorActionPreference = 'Stop'
  }
}

Write-Host 'ℹ️ Checking JDK:'
if (Test-Path 'env:JAVA_HOME') {
  $javaHome = Get-Item 'env:JAVA_HOME' | Select-Object -ExpandProperty Value
  Test-Jdk "$javaHome\bin\java.exe" 21
}
elseif (Get-Command java -ErrorAction SilentlyContinue) {
  Test-Jdk 'java' 21
}
else {
  Write-Host "❌ No Java installation found. Please install JDK 21 or above." -ForegroundColor Red
  exit 1
}
Write-Host 'ℹ️ Checking other JDKs available for testing:'
Show-AvailableJdks

# Check for required commands (e.g., git, docker)
function TestCommand {
  param ($command)

  if (Get-Command $command -ErrorAction SilentlyContinue) {
    Write-Host "✅ The $command command line is installed."
  }
  else {
    Write-Host "❌ The $command command line is missing. Please install $command." -ForegroundColor Red
    exit 1
  }
}

function Get-FileHashMD5 {
  param ($file)
  return (Get-FileHash -Path $file -Algorithm MD5).Hash
}

function TestHook {
  param ($hookName)

  $hookChecksum = Get-FileHashMD5 ".githooks/$hookName"
  $hooksPath = (git config core.hooksPath) -or '.git/hooks'

  if ((Test-Path ".git/hooks/$hookName") -and (Get-FileHashMD5 ".git/hooks/$hookName" -eq $hookChecksum)) {
    Write-Host "✅ $hookName hook is installed in repository."
  }
  elseif ((Test-Path "$hooksPath/$hookName") -and (Get-FileHashMD5 "$hooksPath/$hookName" -eq $hookChecksum)) {
    Write-Host "✅ $hookName hook is installed in git hooks path."
  }
  else {
    Write-Host "🟨 $hookName hook was not found (optional but recommended)."
  }
}

function TestGitConfig {
  param ($configName, $expectedValue)

  $actualValue = git config $configName

  if ($actualValue -eq $expectedValue) {
    Write-Host "✅ git config $configName is set to $expectedValue."
  }
  elseif (-not $actualValue) {
    Write-Host "❌ git config $configName is not set. Please run 'git config set $configName $expectedValue'." -ForegroundColor Red
  }
  else {
    Write-Host "🟨 git config $configName is set to $actualValue (expected: $expectedValue). Please run 'git config set $configName $expectedValue'."
  }
}

function TestSubmoduleInitialization {
  if (Test-Path '.gitmodules') {
    $uninitializedSubmodules = git submodule status | Select-String '^-'
    if ($uninitializedSubmodules) {
      Write-Host "❌ A git submodule are not initialized. Please run 'git submodule update --init --recursive'." -ForegroundColor Red
    }
    else {
      Write-Host "✅ All git submodules are initialized."
    }
  }
}

Write-Host 'ℹ️ Checking git configuration:'
TestCommand 'git'
TestHook 'pre-commit'
TestGitConfig 'submodule.recurse' 'true'
TestSubmoduleInitialization

# Check Docker environment
function TestDockerServer {
  try {
    # try to handle differences between PowerShell 7 and Windows PowerShell 5
    $ErrorActionPreference = 'Continue'
    docker info *> $null

    if ($LASTEXITCODE -eq 0) {
      Write-Host "✅ The Docker server is running."
    }
    else {
      Write-Host "🟨 The Docker server is not running. Please start it to run all tests."
    }
  }
  catch {
    Write-Host "❌ Error running `"docker info *> $null`"."
    exit 1
  }
  finally {
    $ErrorActionPreference = 'Stop'
  }
}

Write-Host 'ℹ️ Checking Docker environment:'
TestCommand 'docker'
TestDockerServer
