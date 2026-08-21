package datadog.buildlogic.conventions

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class ScalaTestingConventionFunctionalTest {

  @TempDir
  lateinit var projectDir: Path

  @Test
  fun `configures Scala testing without applying Groovy`() {
    writeSettings()
    projectDir.resolve("build.gradle").writeText(
      """
      plugins {
        id 'dd-trace-java.conventions.testing.scala'
      }

      tasks.register('verifyScalaTestingConvention') {
        doLast {
          assert plugins.hasPlugin('scala')
          assert !plugins.hasPlugin('groovy')
          assert tasks.findByName('compileTestGroovy') == null

          ['compileOnly', 'testImplementation'].each { configurationName ->
            assert configurations.getByName(configurationName).dependencies.any {
              it.group == 'org.scala-lang' &&
                it.name == 'scala-library' &&
                it.version == '2.11.12'
            }
          }
        }
      }
      """.trimIndent(),
    )

    val result = run("verifyScalaTestingConvention")

    assertThat(result.task(":verifyScalaTestingConvention")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
  }

  @Test
  fun `adds test Scala output when Groovy is applied later`() {
    writeSettings()
    projectDir.resolve("build.gradle").writeText(
      """
      plugins {
        id 'dd-trace-java.conventions.testing.scala'
      }

      apply plugin: 'groovy'

      tasks.register('verifyScalaGroovyInterop') {
        doLast {
          def compileTestGroovy = tasks.named('compileTestGroovy').get()
          def compileTestScala = tasks.named('compileTestScala').get()
          configurations.compileOnly.dependencies.clear()
          configurations.testImplementation.dependencies.clear()
          assert compileTestGroovy.classpath.files.contains(
            compileTestScala.destinationDirectory.get().asFile
          )
          assert compileTestGroovy.taskDependencies
            .getDependencies(compileTestGroovy)
            .contains(compileTestScala)
        }
      }
      """.trimIndent(),
    )

    val result = run("verifyScalaGroovyInterop")

    assertThat(result.task(":verifyScalaGroovyInterop")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
  }

  private fun writeSettings() {
    projectDir.resolve("settings.gradle").writeText(
      """
      dependencyResolutionManagement {
        versionCatalogs {
          libs {
            library('scala', 'org.scala-lang', 'scala-library').version('2.11.12')
          }
        }
      }

      rootProject.name = 'scala-testing-convention-test'
      """.trimIndent(),
    )
  }

  private fun run(task: String) = GradleRunner.create()
    .withProjectDir(projectDir.toFile())
    .withPluginClasspath()
    .withArguments(task, "--stacktrace")
    .build()
}
