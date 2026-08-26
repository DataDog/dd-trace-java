# escape=`

FROM eclipse-temurin:8-jdk-windowsservercore-ltsc2022@sha256:eabbefd4cede69e8127cc87507a690f76c1a953d1eb7d9495c22d645751d4848 AS temurin8
FROM eclipse-temurin:11-jdk-windowsservercore-ltsc2022@sha256:b28aa578a01b67fa30eddc71615369d71560458636f0f74dc39ededbdb9b9a67 AS temurin11
FROM eclipse-temurin:17-jdk-windowsservercore-ltsc2022@sha256:d3e4b6058da8b3b54acb97b708b141650fdb5234b7b5dac92017263e1cf530b3 AS temurin17
FROM eclipse-temurin:25-jdk-windowsservercore-ltsc2022@sha256:ef8836e0ec8a43d98a0d0d0ef7accec7e466e3f67533649b0887ba77cf295a9e AS temurin25

FROM eclipse-temurin:21-jdk-windowsservercore-ltsc2022@sha256:1ef9cebf526129fea284f8b92b4736b6ffcdaaee151603820a787047af13625f

SHELL ["powershell", "-NoLogo", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", "$ErrorActionPreference = 'Stop'; $ProgressPreference = 'SilentlyContinue';"]

USER ContainerAdministrator

COPY --from=temurin8 C:/openjdk-8 C:/openjdk-8
COPY --from=temurin11 C:/openjdk-11 C:/openjdk-11
COPY --from=temurin17 C:/openjdk-17 C:/openjdk-17
COPY --from=temurin25 C:/openjdk-25 C:/openjdk-25

ENV JAVA_8_HOME="C:\openjdk-8"
ENV JAVA_11_HOME="C:\openjdk-11"
ENV JAVA_17_HOME="C:\openjdk-17"
ENV JAVA_21_HOME="C:\openjdk-21"
ENV JAVA_25_HOME="C:\openjdk-25"
ENV JAVA_HOME="C:\openjdk-21"

ARG MINGIT_VERSION="2.55.0.5"
ARG MINGIT_RELEASE="v2.55.0.windows.5"
ARG MINGIT_SHA256="56d7b226b7693196cfc71fef26568f536c4a021ab6c37ff2db4287bed908e96e"

# The classic Windows Docker builder consumes nested double quotes in shell-form
# RUN commands. Use PowerShell single-quoted strings and -f formatting here.
RUN $archive = 'C:\mingit.zip'; `
    $url = 'https://github.com/git-for-windows/git/releases/download/{0}/MinGit-{1}-64-bit.zip' -f $env:MINGIT_RELEASE, $env:MINGIT_VERSION; `
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; `
    (New-Object System.Net.WebClient).DownloadFile($url, $archive); `
    $actual = (Get-FileHash -Path $archive -Algorithm SHA256).Hash.ToLowerInvariant(); `
    if ($actual -ne $env:MINGIT_SHA256) { throw ('MinGit checksum mismatch: expected {0}, got {1}' -f $env:MINGIT_SHA256, $actual) }; `
    Expand-Archive -Path $archive -DestinationPath C:\MinGit -Force; `
    Remove-Item $archive -Force

ENV PATH="C:\MinGit\cmd;C:\MinGit\mingw64\bin;C:\openjdk-21\bin;C:\Windows\System32;C:\Windows;C:\Windows\System32\Wbem;C:\Windows\System32\WindowsPowerShell\v1.0"

# The longest source path in the repository is over 200 characters, and relocated
# build outputs under C:\work\workspace\... push past the 260-character MAX_PATH
# default. The runner sets core.longpaths on the host checkout; the container
# needs both the Win32 opt-in and its own git setting.
RUN Set-ItemProperty `
        -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\FileSystem' `
        -Name 'LongPathsEnabled' -Value 1 -Type DWord; `
    git config --system core.longpaths true

# Native tools can write version information to stderr. Run them with non-terminating
# native error handling, capture their output, and gate each call on its exit code.
RUN $ErrorActionPreference = 'Continue'; `
    foreach ($javaHome in @($env:JAVA_8_HOME, $env:JAVA_11_HOME, $env:JAVA_17_HOME, $env:JAVA_21_HOME, $env:JAVA_25_HOME)) { `
        $java = Join-Path $javaHome 'bin\java.exe'; `
        $output = (& $java -version 2>&1 | Out-String).Trim(); `
        if ($LASTEXITCODE -ne 0) { throw ('java -version failed for {0}' -f $javaHome) }; `
        Write-Host ('{0} -> {1}' -f $javaHome, $output); `
    }; `
    $gitVersion = (git --version 2>&1 | Out-String).Trim(); `
    if ($LASTEXITCODE -ne 0) { throw 'git --version failed' }; `
    Write-Host $gitVersion
