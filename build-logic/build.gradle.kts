plugins {
  base
  alias(libs.plugins.spotless)
}

// Formats the Kotlin sources and build scripts of every build-logic project from here,
// so the projects share one configuration.
spotless {
  // The ktlint rules are in the repository .editorconfig. Spotless only tracks this build's
  // root .editorconfig, so point it at the repository one.
  val editorConfig = rootDir.resolve("../.editorconfig")
  kotlin {
    toggleOffOn()
    target("*/src/**/*.kt")
    targetExclude("*/src/*/resources/**")
    ktlint().setEditorConfigPath(editorConfig)
  }
  kotlinGradle {
    toggleOffOn()
    target("*.gradle.kts", "*/*.gradle.kts", "*/src/**/*.gradle.kts")
    targetExclude("*/src/*/resources/**")
    ktlint().setEditorConfigPath(editorConfig)
  }
}

tasks.check {
  dependsOn(subprojects.map { "${it.path}:check" })
}
