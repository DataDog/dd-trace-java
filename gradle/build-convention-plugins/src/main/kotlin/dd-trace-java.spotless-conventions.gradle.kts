import com.diffplug.gradle.spotless.SpotlessExtension

plugins {
  id("com.diffplug.spotless")
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
        "gradle/build-convention-plugins/build/**",
        "dd-java-agent/agent-jmxfetch/**"
      )

      kotlinGradle {
        toggleOffOn()
        target("**/*.gradle.kts")
        targetExclude(commonExcludes)
        ktlint("1.8.0").editorConfigOverride(
          mapOf(
            "ktlint_standard_trailing-comma-on-call-site" to "disabled",
            "ktlint_standard_trailing-comma-on-declaration-site" to "disabled"
          )
        )
      }

      groovyGradle {
        toggleOffOn()
        target("**/*.gradle")
        targetExclude(commonExcludes)
        greclipse().configFile("$rootDir/gradle/enforcement/spotless-groovy.properties")
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
          tableTestFormatter("1.1.1")
          googleJavaFormat("1.35.0")
        }
      }

      pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        kotlin {
          toggleOffOn()
          target("src/**/*.kt", "app*/**/*.kt")
          targetExclude(commonExcludes)
          ktlint("1.8.0").editorConfigOverride(
            mapOf(
              "ktlint_standard_trailing-comma-on-call-site" to "disabled",
              "ktlint_standard_trailing-comma-on-declaration-site" to "disabled"
            )
          )
        }
      }

      pluginManager.withPlugin("scala") {
        scala {
          toggleOffOn()
          target("src/**/*.scala", "app*/**/*.scala")
          targetExclude(commonExcludes)
          scalafmt("3.11.1").configFile("$rootDir/gradle/enforcement/spotless-scalafmt.conf")
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
