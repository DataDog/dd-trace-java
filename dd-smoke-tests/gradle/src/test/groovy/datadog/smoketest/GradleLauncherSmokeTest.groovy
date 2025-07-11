package datadog.smoketest

import datadog.communication.util.IOUtils
import datadog.trace.civisibility.utils.ShellCommandExecutor

/**
 * This test runs Gradle Launcher with the Java Tracer injected
 * and verifies that the tracer is injected into the Gradle Daemon.
 */
class GradleLauncherSmokeTest extends AbstractGradleTest {

  private static final int GRADLE_BUILD_TIMEOUT_MILLIS = 90_000

  private static final String AGENT_JAR = System.getProperty("datadog.smoketest.agent.shadowJar.path")

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
    def shellCommandExecutor = new ShellCommandExecutor(projectFolder.toFile(), GRADLE_BUILD_TIMEOUT_MILLIS)
    shellCommandExecutor.executeCommand(IOUtils::readFully, "./gradlew", "wrapper", "--gradle-version", gradleVersion)
  }

  private String whenRunningGradleLauncherWithJavaTracerInjected(String gradleDaemonCmdLineParams) {
    def shellCommandExecutor = new ShellCommandExecutor(projectFolder.toFile(), GRADLE_BUILD_TIMEOUT_MILLIS, [
      "GRADLE_OPTS"                        : "-javaagent:${AGENT_JAR}".toString(),
      "DD_CIVISIBILITY_ENABLED"            : "true",
      "DD_CIVISIBILITY_AGENTLESS_ENABLED"  : "true",
      "DD_CIVISIBILITY_AGENTLESS_URL"      : "${mockBackend.intakeUrl}".toString(),
      "DD_CIVISIBILITY_GIT_UPLOAD_ENABLED" : "false",
      "DD_API_KEY"                         : "dummy"
    ])
    String[] command = ["./gradlew", "--no-daemon", "--info"]
    if (gradleDaemonCmdLineParams) {
      command += "-Dorg.gradle.jvmargs=$gradleDaemonCmdLineParams".toString()
    }
    return shellCommandExecutor.executeCommand(IOUtils::readFully, command)
  }

  private static boolean gradleDaemonStartCommandContains(String buildOutput, String... tokens) {
    def daemonStartCommandLog = buildOutput.split("\n").find { it.contains("Starting process 'Gradle build daemon'") }
    for (String token : tokens) {
      if (!daemonStartCommandLog.contains(token)) {
        throw new org.opentest4j.AssertionFailedError("Gradle Daemon start command does not contain " + token, token, daemonStartCommandLog)
      }
    }
    return true
  }
}
