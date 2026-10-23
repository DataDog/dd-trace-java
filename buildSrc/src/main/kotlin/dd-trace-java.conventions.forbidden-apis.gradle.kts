plugins {
  java
  id("de.thetaphi.forbiddenapis")
}

forbiddenApis {
  signaturesFiles = files(rootDir.resolve("gradle/forbiddenApiFilters/main.txt"))
  ignoreSignaturesOfMissingClasses = true
  ignoreFailures = false
}

tasks.matching { it.name.startsWith("forbiddenApis") && it.name.endsWith("Test") }.configureEach {
  enabled = false
}

dependencies {
  compileOnly(versionCatalogs.named("libs").findLibrary("forbiddenapis").get())
}
