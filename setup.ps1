# Enable error handling
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function TestJvm {
  param ($JAVA_HOME_NAME, $EXPECTED_JAVA_VERSION)

  if (-not (Test-Path env:$JAVA_HOME_NAME)) {
    Write-Host "❌ $JAVA_HOME_NAME is not set. Please set $JAVA_HOME_NAME to refer to a JDK $EXPECTED_JAVA_VERSION installation." -ForegroundColor Red
    exit 1
  }
  else {
    $javaHome = env:$JAVA_HOME_NAME

    if (-not (& "$javaHome/bin/java" -version 2>&1 | Select-String "version `"$EXPECTED_JAVA_VERSION")) {
      Write-Host "❌ $JAVA_HOME_NAME is set to $javaHome, but it does not refer to a JDK $EXPECTED_JAVA_VERSION installation." -ForegroundColor Red
      exit 1
    }
    else {
      Write-Host "✅ $JAVA_HOME_NAME is set to $javaHome."
    }
  }
}

Write-Host "ℹ️ Checking required JVM:"
if (Test-Path env:JAVA_HOME) {
  TestJvm "JAVA_HOME" "1.8"
}

TestJvm "JAVA_8_HOME" "1.8"
TestJvm "JAVA_11_HOME" "11"
TestJvm "JAVA_17_HOME" "17"
TestJvm "JAVA_21_HOME" "21"
TestJvm "JAVA_GRAALVM17_HOME" "17"

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
  $hooksPath = (git config core.hooksPath) -or ".git/hooks"

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

Write-Host "ℹ️ Checking git configuration:"
TestCommand "git"
Look-For-Hook "pre-commit"
Check-GitConfig "submodule.recurse" "true"

# Check Docker environment
function TestDockerServer {
  if (docker info -ErrorAction SilentlyContinue) {
    Write-Host "✅ The Docker server is running."
  }
  else {
    Write-Host "🟨 The Docker server is not running. Please start it to run all tests."
  }
}

Write-Host "ℹ️ Checking Docker environment:"
TestCommand "docker"
TestDockerServer

# Check ulimit (not applicable on Windows, placeholder for *nix systems)
function TestUlimit {
  param ($expectedLimit)

  $actualLimit = ulimit -n
  if ($actualLimit -ge $expectedLimit) {
    Write-Host "✅ File descriptor limit is set to $actualLimit."
  }
  else {
    Write-Host "🟨 File descriptor limit is set to $actualLimit, which could be an issue for the build. Please set it to $expectedLimit or greater."
  }
}
