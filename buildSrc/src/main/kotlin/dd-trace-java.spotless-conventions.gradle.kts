import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessExtensionPredeclare

plugins {
  id("com.diffplug.spotless")
}

val googleJavaFormatVersion = "1.35.0"
val tableTestFormatterVersion = "1.1.1"
val ktlintVersion = "1.8.0"
val scalafmtVersion = "3.11.1"
val ktlintEditorConfigOverride = mapOf(
  "ktlint_standard_trailing-comma-on-call-site" to "disabled",
  "ktlint_standard_trailing-comma-on-declaration-site" to "disabled"
)

// Resolve formatter dependencies once, then let all subprojects reuse the cached provisioner.
configure<SpotlessExtensionPredeclare> {
  java {
    tableTestFormatter(tableTestFormatterVersion)
    googleJavaFormat(googleJavaFormatVersion)
  }

  groovyGradle {
    greclipse()
  }

  groovy {
    greclipse()
  }

  kotlinGradle {
    ktlint(ktlintVersion)
  }

  kotlin {
    ktlint(ktlintVersion)
  }

  scala {
    scalafmt(scalafmtVersion)
  }
}

// List of projects to exclude from processing by Spotless.
val spotlessExcludedProjects = setOf(":dd-java-agent:agent-jmxfetch")

// Spotless applied per-module for parallel execution, configured centrally here.
allprojects {
  if (path in spotlessExcludedProjects) {
    return@allprojects
  }

  apply(plugin = "com.diffplug.spotless")

  configure<SpotlessExtension> {
    if (rootProject.hasProperty("skipSpotless")) {
      isEnforceCheck = false
    }

    // Gradle files and other formats we process globally from root
    if (project == rootProject) {
      val commonExcludes = listOf(
        "build/**",
        "buildSrc/build/**",
        "buildSrc/**/build/**",
        "dd-java-agent/agent-jmxfetch/**"
      )

      kotlinGradle {
        toggleOffOn()
        target("**/*.gradle.kts")
        targetExclude(commonExcludes)
        ktlint(ktlintVersion).editorConfigOverride(ktlintEditorConfigOverride)
      }

      groovyGradle {
        toggleOffOn()
        target("**/*.gradle")
        targetExclude(commonExcludes)
        greclipse().configFile("$rootDir/gradle/enforcement/spotless-groovy.properties")
      }

      // buildSrc is a separate Gradle build, so its non-script sources are not
      // covered by the per-subproject configuration below. Format them from root
      // via file-glob targets so a single `./gradlew spotlessApply` covers them.
      kotlin {
        toggleOffOn()
        target("buildSrc/**/*.kt")
        targetExclude(commonExcludes)
        ktlint(ktlintVersion).editorConfigOverride(ktlintEditorConfigOverride)
      }

      java {
        toggleOffOn()
        target("buildSrc/**/*.java")
        targetExclude(commonExcludes)
        tableTestFormatter(tableTestFormatterVersion)
        googleJavaFormat(googleJavaFormatVersion)
      }

      format("markdown") {
        toggleOffOn()
        target("*.md", ".github/**/*.md", "src/**/*.md", "app*/**/*.md")
        leadingTabsToSpaces()
        endWithNewline()
      }

      format("misc") {
        toggleOffOn()
        target(".gitignore", "*.sh", "tooling/*.sh", ".gitlab/*.sh")
        leadingTabsToSpaces()
        trimTrailingWhitespace()
        endWithNewline()
      }
    } else {
      // Configure source code formatting.
      val commonExcludes = listOf("build/**", "src/test/resources/**")

      pluginManager.withPlugin("java") {
        java {
          toggleOffOn()
          target("src/**/*.java", "app*/**/*.java")
          targetExclude(commonExcludes)
          tableTestFormatter(tableTestFormatterVersion)
          googleJavaFormat(googleJavaFormatVersion)
        }
      }

      pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        kotlin {
          toggleOffOn()
          target("src/**/*.kt", "app*/**/*.kt")
          targetExclude(commonExcludes)
          ktlint(ktlintVersion).editorConfigOverride(ktlintEditorConfigOverride)
        }
      }

      pluginManager.withPlugin("scala") {
        scala {
          toggleOffOn()
          target("src/**/*.scala", "app*/**/*.scala")
          targetExclude(commonExcludes)
          scalafmt(scalafmtVersion).configFile("$rootDir/gradle/enforcement/spotless-scalafmt.conf")
        }
      }

      pluginManager.withPlugin("groovy") {
        groovy {
          toggleOffOn()
          target("src/**/*.groovy", "app*/**/*.groovy")
          targetExclude(commonExcludes)
          greclipse().configFile("$rootDir/gradle/enforcement/spotless-groovy.properties")
        }
      }
    }
  }
}
