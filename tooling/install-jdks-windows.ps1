# Enable error handling
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

# winget may not be available for a few minutes if you just signed into Windows for the first time
if (-not (Get-Command 'winget.exe' -ErrorAction SilentlyContinue)) {
  # this command will ensure it is available.
  Write-Host 'WinGet not availalbe. Trying to enable WinGet...'
  Add-AppxPackage -RegisterByFamilyName -MainPackage Microsoft.DesktopAppInstaller_8wekyb3d8bbwe -ErrorAction SilentlyContinue
}

# if winget is still not available, it may not be pre-installed in your Windows version
if (-not (Get-Command 'winget.exe' -ErrorAction SilentlyContinue)) {
  Write-Host 'Installing WinGet PowerShell module from PSGallery...'
  Install-PackageProvider -Name NuGet -Force | Out-Null
  Install-Module -Name Microsoft.WinGet.Client -Force -Repository PSGallery | Out-Null
  Write-Host 'Using Repair-WinGetPackageManager cmdlet to bootstrap WinGet...'
  Repair-WinGetPackageManager
  Write-Host 'WinGet.'
}

# install the required JDKs
$jdkVersions = @('8', '11', '17', '21')

foreach ($jdkVersion in $jdkVersions) {
  Write-Host "ℹ️ Installing EclipseAdoptium.Temurin.${jdkVersion}.JDK"
  winget install --silent --disable-interactivity --accept-package-agreements --source winget --exact --id "EclipseAdoptium.Temurin.${jdkVersion}.JDK"
}

# find the JDK installation paths
if (Test-Path 'C:\Program Files\Eclipse Adoptium') {
  $jdkDirs = Get-ChildItem -Path 'C:\Program Files\Eclipse Adoptium' -Directory
} else {
  Write-Host '❌ Directory "C:\Program Files\Eclipse Adoptium" does not exist. Cannot set environment variables.'
  exit 1
}

# set the required JDK environment variables
foreach ($jdkDir in $jdkDirs) {
  if ($jdkDir.Name -match 'jdk-(\d+)\..*-hotspot') {
    $jdkDirFullName = $jdkDir.FullName
    $version = $matches[1]
    $envVarName = "JAVA_${version}_HOME"
    Write-Host "ℹ️ Setting $envVarName=$jdkDirFullName"
    [System.Environment]::SetEnvironmentVariable($envVarName, $jdkDirFullName, [System.EnvironmentVariableTarget]::User)

    if ($version -eq '8') {
      Write-Host "ℹ️ Setting JAVA_HOME=$jdkDirFullName"
      [System.Environment]::SetEnvironmentVariable('JAVA_HOME', $jdkDirFullName, [System.EnvironmentVariableTarget]::User)
    }
  }
}
