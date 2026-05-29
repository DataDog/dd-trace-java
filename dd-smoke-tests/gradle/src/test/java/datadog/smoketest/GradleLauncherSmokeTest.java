package datadog.smoketest;

import datadog.communication.util.IOUtils;
import datadog.trace.civisibility.utils.ShellCommandExecutor;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.opentest4j.AssertionFailedError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tabletest.junit.TableTest;

/**
 * This test runs Gradle Launcher with the Java Tracer injected and verifies that the tracer is
 * injected into the Gradle Daemon.
 */
class GradleLauncherSmokeTest extends AbstractGradleTest {

  private static final Logger LOGGER = LoggerFactory.getLogger(GradleLauncherSmokeTest.class);

  private static final int GRADLE_BUILD_TIMEOUT_MILLIS = 90_000;
  private static final int GRADLE_WRAPPER_RETRIES = 3;

  private static final String JAVA_HOME = buildJavaHome();

  @TempDir static Path gradleUserHome;

  @TableTest({
    "scenario                 | gradleVersion | gradleDaemonCmdLineParams         ",
    "6.6.1-from-gradle-props  | 6.6.1         |                                   ",
    "6.6.1-from-cmd-line      | 6.6.1         | -Duser.country=VALUE_FROM_CMD_LINE",
    "7.6.4-from-gradle-props  | 7.6.4         |                                   ",
    "7.6.4-from-cmd-line      | 7.6.4         | -Duser.country=VALUE_FROM_CMD_LINE",
    "8.11.1-from-gradle-props | 8.11.1        |                                   ",
    "8.11.1-from-cmd-line     | 8.11.1        | -Duser.country=VALUE_FROM_CMD_LINE",
    "latest-from-gradle-props | latest        |                                   ",
    "latest-from-cmd-line     | latest        | -Duser.country=VALUE_FROM_CMD_LINE"
  })
  @ParameterizedTest
  void testGradleLauncherInjectsTracerIntoGradleDaemon(
      String gradleVersion, String gradleDaemonCmdLineParams) throws Exception {
    String resolvedGradleVersion =
        "latest".equals(gradleVersion) ? LATEST_GRADLE_VERSION : gradleVersion;
    String cmdLineParams =
        (gradleDaemonCmdLineParams == null || gradleDaemonCmdLineParams.isEmpty())
            ? null
            : gradleDaemonCmdLineParams;

    givenGradleVersionIsCompatibleWithCurrentJvm(resolvedGradleVersion);
    Map<String, Map<String, String>> replacements = new HashMap<>();
    Map<String, String> versionMap = new HashMap<>();
    versionMap.put("gradle-version", resolvedGradleVersion);
    versionMap.put(
        "gradle-distribution-url", GradleDistribution.uriPropertiesValueFor(resolvedGradleVersion));
    replacements.put("gradle-wrapper.properties", versionMap);
    givenGradleProjectFiles("test-gradle-wrapper", replacements);
    // we want to check that instrumentation works with different wrapper versions too
    givenGradleWrapper(resolvedGradleVersion);

    String output = whenRunningGradleLauncherWithJavaTracerInjected(cmdLineParams);

    gradleDaemonStartCommandContains(
        output,
        // Verify that the javaagent is injected into the Gradle Daemon start command.
        "-javaagent:" + AGENT_JAR,
        // Verify that existing Gradle Daemon JVM args are preserved: org.gradle.jvmargs provided
        // on the command line (if present), otherwise org.gradle.jvmargs from gradle.properties.
        // "user.country" is used, as Gradle will filter out properties it is not aware of.
        cmdLineParams != null ? cmdLineParams : "-Duser.country=VALUE_FROM_GRADLE_PROPERTIES_FILE");
  }

  private void givenGradleWrapper(String gradleVersion) throws Exception {
    Map<String, String> env = new HashMap<>();
    env.put("JAVA_HOME", JAVA_HOME);
    env.put("GRADLE_USER_HOME", gradleUserHome.toString());
    // Avoid inheriting CI's GRADLE_OPTS which might be incompatible with the tested JVM.
    env.put("GRADLE_OPTS", "");
    ShellCommandExecutor shellCommandExecutor =
        new ShellCommandExecutor(projectFolder.toFile(), GRADLE_BUILD_TIMEOUT_MILLIS, env);

    for (int attempt = 0; attempt < GRADLE_WRAPPER_RETRIES; attempt++) {
      try {
        shellCommandExecutor.executeCommand(
            IOUtils::readFully, "./gradlew", "wrapper", "--gradle-version", gradleVersion);
        GradleDistribution.rewriteWrapperDistributionUrl(projectFolder, gradleVersion);
        return;
      } catch (ShellCommandExecutor.ShellCommandFailedException e) {
        LOGGER.warn("Failed gradle wrapper resolution with exception: ", e);
        Thread.sleep(2000); // small delay for rapid retries on network issues
      }
    }
    throw new AssertionError(
        "Tried " + GRADLE_WRAPPER_RETRIES + " times to execute gradle wrapper command and failed");
  }

  private String whenRunningGradleLauncherWithJavaTracerInjected(String gradleDaemonCmdLineParams)
      throws Exception {
    Map<String, String> env = new HashMap<>();
    env.put("JAVA_HOME", JAVA_HOME);
    env.put("GRADLE_USER_HOME", gradleUserHome.toString());
    env.put("GRADLE_OPTS", "-javaagent:" + AGENT_JAR);
    env.put("DD_CIVISIBILITY_ENABLED", "true");
    env.put("DD_CIVISIBILITY_AGENTLESS_ENABLED", "true");
    env.put("DD_CIVISIBILITY_AGENTLESS_URL", mockBackend.getIntakeUrl());
    env.put("DD_CIVISIBILITY_GIT_UPLOAD_ENABLED", "false");
    env.put("DD_CIVISIBILITY_GIT_CLIENT_ENABLED", "false");
    env.put("DD_CODE_ORIGIN_FOR_SPANS_ENABLED", "false");
    env.put("DD_CIVISIBILITY_CODE_COVERAGE_ENABLED", "false");
    env.put("DD_API_KEY", "dummy");

    ShellCommandExecutor shellCommandExecutor =
        new ShellCommandExecutor(projectFolder.toFile(), GRADLE_BUILD_TIMEOUT_MILLIS, env);

    List<String> command =
        new java.util.ArrayList<>(Arrays.asList("./gradlew", "--no-daemon", "--info"));
    if (gradleDaemonCmdLineParams != null) {
      command.add("-Dorg.gradle.jvmargs=" + gradleDaemonCmdLineParams);
    }

    try {
      return shellCommandExecutor.executeCommand(
          IOUtils::readFully, command.toArray(new String[0]));
    } catch (Exception e) {
      System.out.println("==============================================================");
      System.out.println("Gradle Launcher execution failed with exception:\n " + e.getMessage());
      System.out.println("==============================================================");
      throw e;
    }
  }

  private static void gradleDaemonStartCommandContains(String buildOutput, String... tokens) {
    String daemonStartCommandLog = null;
    for (String line : buildOutput.split("\n")) {
      if (line.contains("Starting process 'Gradle build daemon'")) {
        daemonStartCommandLog = line;
        break;
      }
    }
    for (String token : tokens) {
      if (daemonStartCommandLog == null || !daemonStartCommandLog.contains(token)) {
        throw new AssertionFailedError(
            "Gradle Daemon start command does not contain " + token,
            token,
            String.valueOf(daemonStartCommandLog));
      }
    }
  }
}
