import de.thetaphi.forbiddenapis.gradle.CheckForbiddenApis

plugins {
  java
  id("de.thetaphi.forbiddenapis")
}

// The Forbidden APIs plugin supports Gradle versions back to 3.2 and uses Gradle's legacy
// convention mapping to pass forbiddenApis extension settings to tasks. Scalar settings
// supplied this way can revert to the plugin defaults when Gradle stores the configuration
// cache. Setting them directly on each task preserves them on cache store and reuse.
// See https://github.com/policeman-tools/forbidden-apis/issues/269
tasks.withType<CheckForbiddenApis>().configureEach {
  signaturesFiles = files(rootDir.resolve("gradle/forbiddenApiFilters/main.txt"))
  ignoreFailures = false
  ignoreSignaturesOfMissingClasses = true
}

tasks.matching { it.name.startsWith("forbiddenApis") && it.name.endsWith("Test") }.configureEach {
  enabled = false
}

dependencies {
  compileOnly(versionCatalogs.named("libs").findLibrary("forbiddenapis").get())
}
