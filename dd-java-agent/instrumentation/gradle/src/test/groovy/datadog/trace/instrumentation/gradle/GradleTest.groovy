package datadog.trace.instrumentation.gradle


import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import spock.lang.AutoCleanup
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static datadog.trace.agent.test.server.http.TestHttpServer.httpServer

class GradleTest extends Specification {

  @TempDir
  Path testKitFolder
  @TempDir
  Path projectFolder

  @AutoCleanup
  def testServer = httpServer {
    handlers {
      all {
        println request.body
        response.status(200).send()
      }
    }
  }

  def "test successful build generates spans"() {
    given:

    // FIXME agent path should be calculated dynamically
    // FIXME remove debug
    // FIXME use named constants for property names
    def gradleProperties =
      "org.gradle.jvmargs=-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005 " +
      //      "org.gradle.jvmargs=" +
      "-Ddd.civisibility.enabled=true " +
      "-Ddd.civisibility.agentless.enabled=true " +
      "-Ddd.civisibility.agentless.url=${testServer.address.toString()} " +
      "-Ddd.env=integrationTest " +
      "-Ddd.service=test-gradle-service " +
      "-javaagent:/Users/nikita.tkachenko/.m2/repository/com/datadoghq/dd-java-agent/1.7.0-SNAPSHOT/dd-java-agent-1.7.0-SNAPSHOT.jar=key1=value1,key2=value2"
    // FIXME can we build the JAR before executing this? Or does this have to be a smoke test?
    Files.write(testKitFolder.resolve("gradle.properties"), gradleProperties.getBytes())

    GradleTest.classLoader.getResourceAsStream("datadog/trace/instrumentation/gradle/success.gradle").withCloseable {
      Files.copy(it, projectFolder.resolve("build.gradle"))
    }

    GradleTest.classLoader.getResourceAsStream("datadog/trace/instrumentation/gradle/success_settings.gradle").withCloseable {
      Files.copy(it, projectFolder.resolve("settings.gradle"))
    }

    def relativeTestSourcesPath = Paths.get("src", "test", "java")
    def testSourcesPath = projectFolder.resolve(relativeTestSourcesPath)

    Files.createDirectories(testSourcesPath)

    GradleTest.classLoader.getResourceAsStream("datadog/trace/instrumentation/gradle/TestSucceed.java").withCloseable {
      Files.copy(it, testSourcesPath.resolve("TestSucceed.java"))
    }

    when:
    GradleRunner gradleRunner = GradleRunner.create()
      .withTestKitDir(testKitFolder.toFile())
      .withProjectDir(projectFolder.toFile())
      .withGradleVersion("7.5")
      .withArguments(
      " '-DmyParam=myValue'",
      "--stacktrace",
      "--warning-mode", "all",
      "test"
      )
      .withArguments("-Dmyproperty=value", "-Pmyenv=envvalue")
    def buildResult = gradleRunner.build()

    println buildResult.output // FIXME remove

    then:
    buildResult.tasks != null
    buildResult.tasks.size() > 0
    for (def task : buildResult.tasks) {
      task.outcome == TaskOutcome.SUCCESS
    }
  }
}
