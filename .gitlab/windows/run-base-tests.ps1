$ErrorActionPreference = "Stop"

Set-Location "C:\work"
git config --global --add safe.directory "C:/work"

if ($env:CI_SPLIT -notmatch '^[1-4]/4$') {
    throw "Expected CI_SPLIT to be one of 1/4, 2/4, 3/4, or 4/4; got '$env:CI_SPLIT'"
}
if ([string]::IsNullOrWhiteSpace($env:GRADLE_TARGET)) {
    throw "GRADLE_TARGET is required"
}
if ([string]::IsNullOrWhiteSpace($env:testJvm)) {
    throw "testJvm is required"
}

$split = $env:CI_SPLIT.Split("/")
$env:CI_NODE_INDEX = $split[0]
$env:CI_NODE_TOTAL = $split[1]

$env:GRADLE_USER_HOME = "C:\work\.gradle"
$env:ORG_GRADLE_PROJECT_mavenRepositoryProxy = $env:MAVEN_REPOSITORY_PROXY
$env:ORG_GRADLE_PROJECT_gradlePluginProxy = $env:GRADLE_PLUGIN_PROXY

$javaHomeVariables = (Get-ChildItem Env: | Where-Object Name -Match '^JAVA_[A-Z0-9_]+_HOME$' | Sort-Object Name).Name -Join ','
# This file wins over the project gradle.properties, so org.gradle.jvmargs must
# repeat every flag the project file sets (currently -XX:MaxMetaspaceSize).
$gradleProperties = @(
    "org.gradle.java.installations.auto-detect=false",
    "org.gradle.java.installations.auto-download=false",
    "org.gradle.java.installations.fromEnv=$javaHomeVariables",
    "org.gradle.jvmargs=-Xms1g -Xmx4g -XX:MaxMetaspaceSize=1g -Djava.util.prefs.userRoot=C:/tmp/java-prefs -Ddatadog.forkedMinHeapSize=128M -Ddatadog.forkedMaxHeapSize=1024M -XX:ErrorFile=C:/tmp/hs_err_pid%p.log -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=C:/tmp"
)
New-Item -ItemType Directory -Force -Path $env:GRADLE_USER_HOME | Out-Null
$gradleProperties | Set-Content -Path (Join-Path $env:GRADLE_USER_HOME "gradle.properties") -Encoding ASCII

# Route the Gradle distribution download through the MASS pull-through cache, the
# same way .gitlab-ci.yml does for every Linux job. The edit is reverted before the
# job ends because the GitLab cache key is derived from this file's contents.
$wrapperProperties = "gradle\wrapper\gradle-wrapper.properties"
$originalWrapperProperties = Get-Content -Path $wrapperProperties -Raw
if (-not [string]::IsNullOrWhiteSpace($env:MASS_READ_URL)) {
    $massHost = ($env:MASS_READ_URL -replace '^https://', '').TrimEnd('/')
    (Get-Content -Path $wrapperProperties) `
        -replace '^(distributionUrl=.*)services\.gradle\.org', "`$1$massHost/internal/artifact/services.gradle.org" `
        | Set-Content -Path $wrapperProperties -Encoding ASCII
    Write-Output "Routing the Gradle distribution through $massHost"
} else {
    Write-Warning "MASS_READ_URL is not set; downloading the Gradle distribution directly"
}

try {
    Write-Output "Running $env:GRADLE_TARGET on Java $env:testJvm, partition $env:CI_SPLIT"
    java --version
    git --version
    & .\gradlew.bat --version
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    $gradleArguments = @(
        $env:GRADLE_TARGET,
        "-Dscan.capture-resource-usage=false",
        # Formatting is validated by the dedicated GitLab Spotless job.
        "-x",
        "spotlessCheck",
        # buildSrc is an included build, so it needs qualified exclusions.
        "-x",
        ":buildSrc:modifiable-config-agent:spotlessCheck",
        "-x",
        ":buildSrc:call-site-instrumentation-plugin:spotlessCheck",
        "-PskipFlakyTests",
        "-PtestJvm=$($env:testJvm)",
        "-Pslot=$($env:CI_SPLIT)",
        "--build-cache",
        "--stacktrace",
        "--no-daemon",
        "--parallel",
        "--max-workers=4",
        "--continue"
    )

    & .\gradlew.bat @gradleArguments
    exit $LASTEXITCODE
}
finally {
    Set-Content -Path $wrapperProperties -Value $originalWrapperProperties -NoNewline
}
