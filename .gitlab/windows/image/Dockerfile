# escape=`

FROM eclipse-temurin:8-jdk-windowsservercore-ltsc2025@sha256:0ce93c7ea851ec5ea7d35393d106bd717697865b6111a681964f6eb7147f14c8 AS temurin8
FROM eclipse-temurin:11-jdk-windowsservercore-ltsc2025@sha256:f58946540fe9ae7c685b9d2b40e58746b2332fa9bd8546ec4d2881143d9da99e AS temurin11
FROM eclipse-temurin:17-jdk-windowsservercore-ltsc2025@sha256:aa7cfb793cd567a3bb2178600c7179249e6058f7a0b9b9e3b44be4cb12bde11b AS temurin17
FROM eclipse-temurin:25-jdk-windowsservercore-ltsc2025@sha256:28a4b5421e4c03e4a86c900971fd835c1b343564a972da324bdabf666766952b AS temurin25

FROM eclipse-temurin:21-jdk-windowsservercore-ltsc2025@sha256:8828b43d3c6be114c39da1696c48d3b76ee46ab4bf08b5afeaf057174b60a9f5

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

RUN $archive = 'C:\mingit.zip'; `
    $url = "https://github.com/git-for-windows/git/releases/download/$env:MINGIT_RELEASE/MinGit-$env:MINGIT_VERSION-64-bit.zip"; `
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; `
    (New-Object System.Net.WebClient).DownloadFile($url, $archive); `
    $actual = (Get-FileHash -Path $archive -Algorithm SHA256).Hash.ToLowerInvariant(); `
    if ($actual -ne $env:MINGIT_SHA256) { throw "MinGit checksum mismatch: expected $env:MINGIT_SHA256, got $actual" }; `
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

# java -version and git --version write to stderr, which PowerShell turns into a
# terminating error under the base image's $ErrorActionPreference = 'Stop'.
# Capture the output and gate on the exit code instead.
RUN foreach ($javaHome in @($env:JAVA_8_HOME, $env:JAVA_11_HOME, $env:JAVA_17_HOME, $env:JAVA_21_HOME, $env:JAVA_25_HOME)) { `
        $output = (& "$javaHome\bin\java.exe" -version 2>&1 | Out-String).Trim(); `
        if ($LASTEXITCODE -ne 0) { throw "java -version failed for $javaHome" }; `
        Write-Host "$javaHome -> $output"; `
    }; `
    $gitVersion = (git --version 2>&1 | Out-String).Trim(); `
    if ($LASTEXITCODE -ne 0) { throw 'git --version failed' }; `
    Write-Host $gitVersion
