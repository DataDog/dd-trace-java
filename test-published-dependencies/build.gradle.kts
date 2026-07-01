plugins {
  id("com.diffplug.spotless") version "8.4.0"
}

val sharedConfigDirectory = "$rootDir/../gradle"
rootProject.extra["sharedConfigDirectory"] = sharedConfigDirectory

val isCI = providers.environmentVariable("CI").isPresent
val versionFromFile = file("$rootDir/${if (isCI) "../workspace" else ".."}/build/main.version").readLines().first()

allprojects {
  group = "com.datadoghq"
  version = versionFromFile

  apply(from = "$sharedConfigDirectory/repositories.gradle")
  apply(from = "$sharedConfigDirectory/spotless.gradle")
}
