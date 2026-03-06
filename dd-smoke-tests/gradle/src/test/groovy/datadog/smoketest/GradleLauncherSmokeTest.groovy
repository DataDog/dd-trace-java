package datadog.smoketest

import datadog.communication.util.IOUtils
import datadog.trace.civisibility.utils.ShellCommandExecutor
import org.opentest4j.AssertionFailedError
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * This test runs Gradle Launcher with the Java Tracer injected
 * and verifies that the tracer is injected into the Gradle Daemon.
 */
class GradleLauncherSmokeTest extends AbstractGradleTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(GradleLauncherSmokeTest)

  private static final int GRADLE_BUILD_TIMEOUT_MILLIS = 90_000
  private static final int GRADLE_WRAPPER_RETRIES = 3

  private static final String JAVA_HOME = buildJavaHome()

  def "test Gradle Launcher injects tracer into Gradle Daemon: v#gradleVersion, cmd line - #gradleDaemonCmdLineParams"() {
    given:
    givenGradleVersionIsCompatibleWithCurrentJvm(gradleVersion)
    givenGradleProjectFiles("test-gradle-wrapper", ["gradle-wrapper.properties": ["gradle-version": gradleVersion]])
    givenGradleWrapper(gradleVersion) // we want to check that instrumentation works with different wrapper versions too

    when:
    def output = whenRunningGradleLauncherWithJavaTracerInjected(gradleDaemonCmdLineParams)

    then:
    gradleDaemonStartCommandContains(output,
    // verify that the javaagent is injected into the Gradle Daemon start command
    "-javaagent:${AGENT_JAR}",
    // verify that existing Gradle Daemon JVM args are preserved:
    // org.gradle.jvmargs provided on the command line, if present,
    // otherwise org.gradle.jvmargs from gradle.properties file
    // ("user.country" is used, as Gradle will filter out properties it is not aware of)
    gradleDaemonCmdLineParams ? gradleDaemonCmdLineParams : "-Duser.country=VALUE_FROM_GRADLE_PROPERTIES_FILE")

    where:
    gradleVersion         | gradleDaemonCmdLineParams
    "6.6.1"               | null
    "6.6.1"               | "-Duser.country=VALUE_FROM_CMD_LINE"
    "7.6.4"               | null
    "7.6.4"               | "-Duser.country=VALUE_FROM_CMD_LINE"
    "8.11.1"              | null
    "8.11.1"              | "-Duser.country=VALUE_FROM_CMD_LINE"
    LATEST_GRADLE_VERSION | null
    LATEST_GRADLE_VERSION | "-Duser.country=VALUE_FROM_CMD_LINE"
  }

  private void givenGradleWrapper(String gradleVersion) {
    def shellCommandExecutor = new ShellCommandExecutor(
    projectFolder.toFile(),
    GRADLE_BUILD_TIMEOUT_MILLIS,
    [
      "JAVA_HOME": JAVA_HOME,
      "GRADLE_OPTS": "" // avoids inheriting CI's GRADLE_OPTS which might be incompatible with the tested JVM
    ])

    for (int attempt = 0; attempt < GRADLE_WRAPPER_RETRIES; attempt++) {
      try {
        shellCommandExecutor.executeCommand(IOUtils::readFully, "./gradlew", "wrapper", "--gradle-version", gradleVersion)
        return
      } catch (ShellCommandExecutor.ShellCommandFailedException e) {
        LOGGER.warn("Failed gradle wrapper resolution with exception: ", e)
        Thread.sleep(2000) // small delay for rapid retries on network issues
      }
    }
    throw new AssertionError((Object) "Tried $GRADLE_WRAPPER_RETRIES times to execute gradle wrapper command and failed")
  }

  private String whenRunningGradleLauncherWithJavaTracerInjected(String gradleDaemonCmdLineParams) {
    def shellCommandExecutor = new ShellCommandExecutor(projectFolder.toFile(), GRADLE_BUILD_TIMEOUT_MILLIS, [
      "JAVA_HOME"                         : JAVA_HOME,
      "GRADLE_OPTS"                       : "-javaagent:${AGENT_JAR}".toString(),
      "DD_CIVISIBILITY_ENABLED"           : "true",
      "DD_CIVISIBILITY_AGENTLESS_ENABLED" : "true",
      "DD_CIVISIBILITY_AGENTLESS_URL"     : "${mockBackend.intakeUrl}".toString(),
      "DD_CIVISIBILITY_GIT_UPLOAD_ENABLED": "false",
      "DD_CIVISIBILITY_GIT_CLIENT_ENABLED": "false",
      "DD_CODE_ORIGIN_FOR_SPANS_ENABLED"  : "false",
      "DD_API_KEY"                        : "dummy"
    ])
    String[] command = ["./gradlew", "--no-daemon", "--info"]
    if (gradleDaemonCmdLineParams) {
      command += "-Dorg.gradle.jvmargs=$gradleDaemonCmdLineParams".toString()
    }

    try {
      return shellCommandExecutor.executeCommand(IOUtils::readFully, command)
    } catch (Exception e) {
      println "=============================================================="
      println "${new Date()}: $specificationContext.currentIteration.displayName - Gradle Launcher execution failed with exception:\n ${e.message}"
      println "=============================================================="
      throw e
    }
  }

  private static boolean gradleDaemonStartCommandContains(String buildOutput, String... tokens) {
    def daemonStartCommandLog = buildOutput.split("\n").find { it.contains("Starting process 'Gradle build daemon'") }
    for (String token : tokens) {
      if (!daemonStartCommandLog.contains(token)) {
        throw new AssertionFailedError("Gradle Daemon start command does not contain " + token, token, daemonStartCommandLog)
      }
    }
    return true
  }
}
