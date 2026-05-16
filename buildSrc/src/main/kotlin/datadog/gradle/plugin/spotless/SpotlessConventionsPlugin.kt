package datadog.gradle.plugin.spotless

import com.diffplug.gradle.spotless.FormatExtension
import com.diffplug.gradle.spotless.GroovyExtension
import com.diffplug.gradle.spotless.GroovyGradleExtension
import com.diffplug.gradle.spotless.JavaExtension
import com.diffplug.gradle.spotless.KotlinExtension
import com.diffplug.gradle.spotless.KotlinGradleExtension
import com.diffplug.gradle.spotless.ScalaExtension
import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessExtensionPredeclare
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project

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
    project.extensions.configure(
      SpotlessExtensionPredeclare::class.java,
      object : Action<SpotlessExtensionPredeclare> {
        override fun execute(spotlessPredeclare: SpotlessExtensionPredeclare) {
          spotlessPredeclare.java(object : Action<JavaExtension> {
            override fun execute(java: JavaExtension) {
              java.tableTestFormatter(TABLE_TEST_FORMATTER_VERSION)
              java.googleJavaFormat(GOOGLE_JAVA_FORMAT_VERSION)
            }
          })

          spotlessPredeclare.groovyGradle(object : Action<GroovyGradleExtension> {
            override fun execute(groovyGradle: GroovyGradleExtension) {
              groovyGradle.greclipse()
            }
          })

          spotlessPredeclare.groovy(object : Action<GroovyExtension> {
            override fun execute(groovy: GroovyExtension) {
              groovy.greclipse()
            }
          })

          spotlessPredeclare.kotlinGradle(object : Action<KotlinGradleExtension> {
            override fun execute(kotlinGradle: KotlinGradleExtension) {
              kotlinGradle.ktlint(KTLINT_VERSION)
            }
          })

          spotlessPredeclare.kotlin(object : Action<KotlinExtension> {
            override fun execute(kotlin: KotlinExtension) {
              kotlin.ktlint(KTLINT_VERSION)
            }
          })

          spotlessPredeclare.scala(object : Action<ScalaExtension> {
            override fun execute(scala: ScalaExtension) {
              scala.scalafmt(SCALAFMT_VERSION)
            }
          })
        }
      }
    )
  }

  private fun configureRootFormatting(project: Project) {
    project.extensions.configure(
      SpotlessExtension::class.java,
      object : Action<SpotlessExtension> {
        override fun execute(spotless: SpotlessExtension) {
          configureSkipSpotless(project, spotless)

          val rootExcludes = listOf(
            "build/**",
            "buildSrc/build/**",
            "buildSrc/**/build/**",
            "test-published-dependencies/**/build/**"
          )

          spotless.kotlinGradle(object : Action<KotlinGradleExtension> {
            override fun execute(kotlinGradle: KotlinGradleExtension) {
              kotlinGradle.toggleOffOn()
              kotlinGradle.target(
                "*.gradle.kts",
                "buildSrc/**/*.gradle.kts",
                "gradle/**/*.gradle.kts",
                "test-published-dependencies/**/*.gradle.kts"
              )
              kotlinGradle.targetExclude(rootExcludes)
              kotlinGradle.ktlint(KTLINT_VERSION)
                .editorConfigOverride(KTLINT_EDITOR_CONFIG_OVERRIDE)
            }
          })

          spotless.groovyGradle(object : Action<GroovyGradleExtension> {
            override fun execute(groovyGradle: GroovyGradleExtension) {
              groovyGradle.toggleOffOn()
              groovyGradle.target(
                "*.gradle",
                "buildSrc/**/*.gradle",
                "gradle/**/*.gradle",
                "test-published-dependencies/**/*.gradle"
              )
              groovyGradle.targetExclude(rootExcludes)
              groovyGradle.greclipse().configFile("${project.rootDir}/gradle/enforcement/spotless-groovy.properties")
            }
          })

          spotless.kotlin(object : Action<KotlinExtension> {
            override fun execute(kotlin: KotlinExtension) {
              kotlin.toggleOffOn()
              kotlin.target("buildSrc/**/*.kt")
              kotlin.targetExclude(rootExcludes)
              kotlin.ktlint(KTLINT_VERSION)
                .editorConfigOverride(KTLINT_EDITOR_CONFIG_OVERRIDE)
            }
          })

          spotless.java(object : Action<JavaExtension> {
            override fun execute(java: JavaExtension) {
              java.toggleOffOn()
              java.target("buildSrc/**/*.java", "test-published-dependencies/**/src/**/*.java")
              java.targetExclude(rootExcludes)
              java.tableTestFormatter(TABLE_TEST_FORMATTER_VERSION)
              java.googleJavaFormat(GOOGLE_JAVA_FORMAT_VERSION)
            }
          })

          spotless.format(
            "markdown",
            object : Action<FormatExtension> {
              override fun execute(markdown: FormatExtension) {
                markdown.toggleOffOn()
                markdown.target("*.md", ".github/**/*.md", "src/**/*.md", "app*/**/*.md")
                markdown.leadingTabsToSpaces()
                markdown.endWithNewline()
              }
            }
          )

          spotless.format(
            "misc",
            object : Action<FormatExtension> {
              override fun execute(misc: FormatExtension) {
                misc.toggleOffOn()
                misc.target(".gitignore", "*.sh", "tooling/*.sh", ".gitlab/*.sh")
                misc.leadingTabsToSpaces()
                misc.trimTrailingWhitespace()
                misc.endWithNewline()
              }
            }
          )
        }
      }
    )
  }

  private fun configureProjectFormatting(project: Project) {
    project.extensions.configure(
      SpotlessExtension::class.java,
      object : Action<SpotlessExtension> {
        override fun execute(spotless: SpotlessExtension) {
          configureSkipSpotless(project, spotless)

          val commonExcludes = listOf("build/**", "src/test/resources/**")

          spotless.kotlinGradle(object : Action<KotlinGradleExtension> {
            override fun execute(kotlinGradle: KotlinGradleExtension) {
              kotlinGradle.toggleOffOn()
              kotlinGradle.target("*.gradle.kts")
              kotlinGradle.ktlint(KTLINT_VERSION)
                .editorConfigOverride(KTLINT_EDITOR_CONFIG_OVERRIDE)
            }
          })

          spotless.groovyGradle(object : Action<GroovyGradleExtension> {
            override fun execute(groovyGradle: GroovyGradleExtension) {
              groovyGradle.toggleOffOn()
              groovyGradle.target("*.gradle")
              groovyGradle.greclipse().configFile("${project.rootDir}/gradle/enforcement/spotless-groovy.properties")
            }
          })

          project.pluginManager.withPlugin("java") {
            spotless.java(object : Action<JavaExtension> {
              override fun execute(java: JavaExtension) {
                java.toggleOffOn()
                java.target("src/**/*.java", "app*/**/*.java")
                java.targetExclude(commonExcludes)
                java.tableTestFormatter(TABLE_TEST_FORMATTER_VERSION)
                java.googleJavaFormat(GOOGLE_JAVA_FORMAT_VERSION)
              }
            })
          }

          project.pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
            spotless.kotlin(object : Action<KotlinExtension> {
              override fun execute(kotlin: KotlinExtension) {
                kotlin.toggleOffOn()
                kotlin.target("src/**/*.kt", "app*/**/*.kt")
                kotlin.targetExclude(commonExcludes)
                kotlin.ktlint(KTLINT_VERSION)
                  .editorConfigOverride(KTLINT_EDITOR_CONFIG_OVERRIDE)
              }
            })
          }

          project.pluginManager.withPlugin("scala") {
            spotless.scala(object : Action<ScalaExtension> {
              override fun execute(scala: ScalaExtension) {
                scala.toggleOffOn()
                scala.target("src/**/*.scala", "app*/**/*.scala")
                scala.targetExclude(commonExcludes)
                scala.scalafmt(SCALAFMT_VERSION)
                  .configFile("${project.rootDir}/gradle/enforcement/spotless-scalafmt.conf")
              }
            })
          }

          project.pluginManager.withPlugin("groovy") {
            spotless.groovy(object : Action<GroovyExtension> {
              override fun execute(groovy: GroovyExtension) {
                groovy.toggleOffOn()
                groovy.target("src/**/*.groovy", "app*/**/*.groovy")
                groovy.targetExclude(commonExcludes)
                groovy.greclipse().configFile("${project.rootDir}/gradle/enforcement/spotless-groovy.properties")
              }
            })
          }
        }
      }
    )
  }

  private fun configureSkipSpotless(project: Project, spotless: SpotlessExtension) {
    if (project.rootProject.hasProperty("skipSpotless")) {
      spotless.setEnforceCheck(false)
    }
  }
}
