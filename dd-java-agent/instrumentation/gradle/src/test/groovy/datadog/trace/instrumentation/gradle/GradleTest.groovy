package datadog.trace.instrumentation.gradle

import datadog.trace.agent.test.AgentTestRunner
import org.codehaus.groovy.tools.FileSystemCompiler
import org.gradle.testkit.runner.GradleRunner
import spock.lang.Ignore

import java.nio.file.Files
import java.nio.file.Path

@Ignore
class GradleTest extends AgentTestRunner {

  private static Path testKitDirectory

  def setupSpec() {
    testKitDirectory = Files.createTempDirectory("gradle-test-kit-")
  }

  def cleanupSpec() {
    FileSystemCompiler.deleteRecursive(testKitDirectory.toFile())
  }

  private Path projectDirectory

  def setup() {
    projectDirectory = Files.createTempDirectory("gradle-")
  }

  def cleanup() {
    FileSystemCompiler.deleteRecursive(projectDirectory.toFile())
  }

  def "test successful build generates spans"() {
    given:
    GradleTest.classLoader.getResourceAsStream("datadog/trace/instrumentation/gradle/success.gradle").withCloseable {
      Files.copy(it, projectDirectory.resolve("build.gradle"))
    }

    GradleTest.classLoader.getResourceAsStream("datadog/trace/instrumentation/gradle/success_settings.gradle").withCloseable {
      Files.copy(it, projectDirectory.resolve("settings.gradle"))
    }

    when:
    GradleRunner gradleRunner = GradleRunner.create()
      .withTestKitDir(testKitDirectory.toFile())
      .withProjectDir(projectDirectory.toFile())
      .withGradleVersion("7.5")
      .withArguments(
      "--stacktrace",
      "--warning-mode", "all"
      )
      .withEnvironment(Collections.emptyMap())
    def buildResult = gradleRunner.build()

    then:
    buildResult.tasks != null
  }
}
