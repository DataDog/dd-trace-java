package datadog.buildlogic.conventions

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class Slf4jSimpleConventionFunctionalTest {

  @TempDir
  lateinit var projectDir: Path

  @Test
  fun `configures existing and late-created test configurations and tasks`() {
    projectDir.resolve("settings.gradle").writeText(
      """
      dependencyResolutionManagement {
        versionCatalogs {
          libs {
            version('slf4j', '1.7.30')
          }
        }
      }

      rootProject.name = 'slf4j-simple-test'
      """.trimIndent(),
    )
    projectDir.resolve("build.gradle").writeText(
      """
      plugins {
        id 'java'
        id 'dd-trace-java.conventions.testing.slf4j-simple'
      }

      configurations {
        lateTESTRuntimeClasspath
        lateTESTRuntimeOnly
        lateRuntimeClasspath
        lateRuntimeOnly
      }
      tasks.register('lateCustomTest', Test)
      def configuredTestTasks = [
        tasks.named('test', Test).get(),
        tasks.named('lateCustomTest', Test).get()
      ]

      tasks.register('verifySlf4jSimpleConvention') {
        doLast {
          ['testRuntimeClasspath', 'lateTESTRuntimeClasspath'].each { configurationName ->
            def configuration = configurations.getByName(configurationName)
            assert configuration.excludeRules.any { it.group == 'ch.qos.logback' }
          }

          ['testRuntimeOnly', 'lateTESTRuntimeOnly'].each { configurationName ->
            def configuration = configurations.getByName(configurationName)
            assert configuration.dependencies.any {
              it.group == 'org.slf4j' && it.name == 'slf4j-simple' && it.version == '1.7.30'
            }
          }

          ['runtimeClasspath', 'lateRuntimeClasspath'].each { configurationName ->
            assert configurations.getByName(configurationName).excludeRules.empty
          }
          ['runtimeOnly', 'lateRuntimeOnly'].each { configurationName ->
            assert configurations.getByName(configurationName).dependencies.empty
          }

          def expectedSystemProperties = [
            'org.slf4j.simpleLogger.defaultLogLevel': 'debug',
            'org.slf4j.simpleLogger.showThreadName': 'true',
            'org.slf4j.simpleLogger.showDateTime': 'true',
            'org.slf4j.simpleLogger.dateTimeFormat': 'HH:mm:ss.SSS'
          ]
          configuredTestTasks.each { testTask ->
            assert expectedSystemProperties.every { key, value ->
              testTask.systemProperties[key] == value
            }
          }
        }
      }
      """.trimIndent(),
    )

    val result = GradleRunner.create()
      .withProjectDir(projectDir.toFile())
      .withPluginClasspath()
      .withArguments("verifySlf4jSimpleConvention", "--stacktrace")
      .build()

    assertThat(result.task(":verifySlf4jSimpleConvention")?.outcome)
      .isEqualTo(TaskOutcome.SUCCESS)
  }
}
