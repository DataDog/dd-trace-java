package datadog.gradle.plugin.spotless

import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessExtensionPredeclare
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class SpotlessConventionsPlugin : Plugin<Project> {
  private companion object {
    const val GOOGLE_JAVA_FORMAT_VERSION = "1.35.0"
    const val TABLE_TEST_FORMATTER_VERSION = "1.1.1"
    const val KTLINT_VERSION = "1.8.0"
    const val SCALAFMT_VERSION = "3.11.1"

    val KTLINT_EDITOR_CONFIG_OVERRIDE =
      mapOf(
        "ktlint_standard_trailing-comma-on-call-site" to "disabled",
        "ktlint_standard_trailing-comma-on-declaration-site" to "disabled"
      )

    val EXCLUDED_PROJECTS = setOf(":dd-java-agent:agent-jmxfetch")
  }

  override fun apply(project: Project) {
    if (project.path in EXCLUDED_PROJECTS) {
      return
    }

    project.pluginManager.apply("com.diffplug.spotless")

    if (project == project.rootProject) {
      configurePredeclaredDependencies(project)
      configureRootFormatting(project)
    } else {
      configureProjectFormatting(project)
    }
  }

  private fun configurePredeclaredDependencies(project: Project) {
    project.extensions.configure<SpotlessExtensionPredeclare> {
      java {
        tableTestFormatter(TABLE_TEST_FORMATTER_VERSION)
        googleJavaFormat(GOOGLE_JAVA_FORMAT_VERSION)
      }

      groovyGradle {
        greclipse()
      }

      groovy {
        greclipse()
      }

      kotlinGradle {
        ktlint(KTLINT_VERSION)
      }

      kotlin {
        ktlint(KTLINT_VERSION)
      }

      scala {
        scalafmt(SCALAFMT_VERSION)
      }
    }
  }

  private fun configureRootFormatting(project: Project) {
    project.extensions.configure<SpotlessExtension> {
      configureSkipSpotless(project, this)

      val rootExcludes = listOf(
        "build/**",
        "buildSrc/build/**",
        "buildSrc/**/build/**",
        "test-published-dependencies/**/build/**"
      )

      kotlinGradle {
        toggleOffOn()
        target(
          "*.gradle.kts",
          "buildSrc/**/*.gradle.kts",
          "gradle/**/*.gradle.kts",
          "test-published-dependencies/**/*.gradle.kts"
        )
        targetExclude(rootExcludes)
        ktlint(KTLINT_VERSION)
          .editorConfigOverride(KTLINT_EDITOR_CONFIG_OVERRIDE)
      }

      groovyGradle {
        toggleOffOn()
        target(
          "*.gradle",
          "buildSrc/**/*.gradle",
          "gradle/**/*.gradle",
          "test-published-dependencies/**/*.gradle"
        )
        targetExclude(rootExcludes)
        greclipse().configFile("${project.rootDir}/gradle/enforcement/spotless-groovy.properties")
      }

      kotlin {
        toggleOffOn()
        target("buildSrc/**/*.kt")
        targetExclude(rootExcludes)
        ktlint(KTLINT_VERSION)
          .editorConfigOverride(KTLINT_EDITOR_CONFIG_OVERRIDE)
      }

      java {
        toggleOffOn()
        target("buildSrc/**/*.java", "test-published-dependencies/**/src/**/*.java")
        targetExclude(rootExcludes)
        tableTestFormatter(TABLE_TEST_FORMATTER_VERSION)
        googleJavaFormat(GOOGLE_JAVA_FORMAT_VERSION)
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
    }
  }

  private fun configureProjectFormatting(project: Project) {
    project.extensions.configure<SpotlessExtension> {
      configureSkipSpotless(project, this)

      val commonExcludes = listOf("build/**", "src/test/resources/**")

      kotlinGradle {
        toggleOffOn()
        target("*.gradle.kts")
        ktlint(KTLINT_VERSION)
          .editorConfigOverride(KTLINT_EDITOR_CONFIG_OVERRIDE)
      }

      groovyGradle {
        toggleOffOn()
        target("*.gradle")
        greclipse().configFile("${project.rootDir}/gradle/enforcement/spotless-groovy.properties")
      }

      project.pluginManager.withPlugin("java") {
        java {
          toggleOffOn()
          target("src/**/*.java", "app*/**/*.java")
          targetExclude(commonExcludes)
          tableTestFormatter(TABLE_TEST_FORMATTER_VERSION)
          googleJavaFormat(GOOGLE_JAVA_FORMAT_VERSION)
        }
      }

      project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        kotlin {
          toggleOffOn()
          target("src/**/*.kt", "app*/**/*.kt")
          targetExclude(commonExcludes)
          ktlint(KTLINT_VERSION)
            .editorConfigOverride(KTLINT_EDITOR_CONFIG_OVERRIDE)
        }
      }

      project.pluginManager.withPlugin("scala") {
        scala {
          toggleOffOn()
          target("src/**/*.scala", "app*/**/*.scala")
          targetExclude(commonExcludes)
          scalafmt(SCALAFMT_VERSION)
            .configFile("${project.rootDir}/gradle/enforcement/spotless-scalafmt.conf")
        }
      }

      project.pluginManager.withPlugin("groovy") {
        groovy {
          toggleOffOn()
          target("src/**/*.groovy", "app*/**/*.groovy")
          targetExclude(commonExcludes)
          greclipse().configFile("${project.rootDir}/gradle/enforcement/spotless-groovy.properties")
        }
      }
    }
  }

  private fun configureSkipSpotless(project: Project, spotless: SpotlessExtension) {
    if (project.rootProject.hasProperty("skipSpotless")) {
      spotless.setEnforceCheck(false)
    }
  }
}
