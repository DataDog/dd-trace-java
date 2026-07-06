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
  apply(plugin = "com.diffplug.spotless")

  spotless {
    kotlinGradle {
      target("*.gradle.kts")
      ktlint("1.8.0").editorConfigOverride(
        mapOf(
          // Disable trailing comma rules to minimize diff.
          "ktlint_standard_trailing-comma-on-call-site" to "disabled",
          "ktlint_standard_trailing-comma-on-declaration-site" to "disabled",
        ),
      )
    }
    java {
      target("src/**/*.java")
      googleJavaFormat("1.35.0")
    }
  }
}
