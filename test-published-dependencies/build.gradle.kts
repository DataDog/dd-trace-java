plugins {
  alias(libs.plugins.spotless)
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

  // Apply a simple spotless config here.
  // Using the dd-trace-java's plugin scripts, adds a step with grecplipse that
  // has a concurrency bug surfacing with gradle 9.6 when there's no matching file.
  spotless {
    kotlinGradle {
      target("*.gradle.kts")
      ktlint(libs.versions.ktlint.get()).editorConfigOverride(
        mapOf(
          // Disable trailing comma rules to minimize diff.
          "ktlint_standard_trailing-comma-on-call-site" to "disabled",
          "ktlint_standard_trailing-comma-on-declaration-site" to "disabled",
        ),
      )
    }
  }

  pluginManager.withPlugin("java") {
    spotless {
      java {
        target("src/**/*.java")
        removeUnusedImports()
        forbidWildcardImports()
        googleJavaFormat(libs.versions.google.java.format.get())
      }
    }
  }
}
