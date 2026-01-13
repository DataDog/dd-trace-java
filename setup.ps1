<#
  .DESCRIPTION
    This script checks the development environment for required tools and configurations.
#>

# Enable error handling
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Check for required JDKs
function TestJvm {
  param ($JavaHomeName, $ExpectedJavaVersion)

  if (-not (Test-Path "env:$JavaHomeName")) {
    Write-Host "❌ $JavaHomeName is not set. Please set $JavaHomeName to refer to a JDK $ExpectedJavaVersion installation." -ForegroundColor Red
    exit 1
  }
  else {
    $javaHome = Get-Item "env:$JavaHomeName" | Select-Object -ExpandProperty Value

    try {
      # try to handle differences between PowerShell 7 and Windows PowerShell 5
      $ErrorActionPreference = 'Continue'
      $javaVersionOutput = & "$javaHome\bin\java.exe" -version 2>&1
    }
    catch {
      Write-Host "❌ Error running `"$javaHome\bin\java.exe -version`". Please check that $JavaHomeName is set to a JDK $ExpectedJavaVersion installation."
      exit 1
    }
    finally {
      $ErrorActionPreference = 'Stop'
    }

    if ($javaVersionOutput[0] -notmatch "version `"$ExpectedJavaVersion") {
      Write-Host "❌ $JavaHomeName is set to $javaHome, but it does not refer to a JDK $ExpectedJavaVersion installation." -ForegroundColor Red
      exit 1
    }
    else {
      Write-Host "✅ $JavaHomeName is set to $javaHome."
    }
  }
}

Write-Host 'ℹ️ Checking required JVM:'
if (Test-Path 'env:JAVA_HOME') {
  TestJvm 'JAVA_HOME' '21'
}
Write-Host 'ℹ️ Other JDK versions will be automatically downloaded by Gradle toolchain resolver.'

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
    Write-Host "❌ git config $configName is not set. Please set it to $expectedValue." -ForegroundColor Red
  }
  else {
    Write-Host "🟨 git config $configName is set to $actualValue (expected: $expectedValue)."
  }
}

Write-Host 'ℹ️ Checking git configuration:'
TestCommand 'git'
TestHook 'pre-commit'
TestGitConfig 'submodule.recurse' 'true'

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
