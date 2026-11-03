package datadog.smoketest.jmx;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class NativeImageJmxSmokeTest {
  @Test
  void buildsWithAgentAfterBuildTimeMxBeanRegistration() throws Exception {
    Path directory = newBuildDirectory();

    // The control must pass first: the fixture alone must not retain the builder's MBean server.
    runImage(directory, "control", true);
    runImage(directory, "withAgent", true);
  }

  @Test
  void supportsRuntimeJmxAfterBuildTimeMxBeanRegistration() throws Exception {
    assertTrue(
        runImage(newBuildDirectory(), "runtimeJmx", true)
            .contains("Runtime JMX metrics available"));
  }

  @Test
  void supportsRuntimeJmxWithDefaultClassInitialization() throws Exception {
    assertTrue(
        runImage(newBuildDirectory(), "runtimeOnly", false)
            .contains("Runtime JMX server available"));
  }

  private Path newBuildDirectory() throws Exception {
    Path buildDirectory = Paths.get(System.getProperty("datadog.smoketest.builddir"));
    Files.createDirectories(buildDirectory);
    return Files.createTempDirectory(buildDirectory, "native-image-jmx-");
  }

  private String runImage(Path directory, String scenario, boolean initializeAtBuildTime)
      throws Exception {
    Path manifest = Paths.get(System.getProperty("datadog.smoketest.jmx.native.manifest"));
    Properties artifacts = new Properties();
    try (InputStream input = Files.newInputStream(manifest)) {
      artifacts.load(input);
    }
    Path executable = manifest.getParent().resolve(artifacts.getProperty(scenario + ".executable"));
    Path buildLog = manifest.getParent().resolve(artifacts.getProperty(scenario + ".buildLog"));
    String output = new String(Files.readAllBytes(buildLog), UTF_8);
    assertEquals(
        initializeAtBuildTime,
        output.contains("Build-time MXBean registered"),
        () -> "Fixture did not run: " + buildLog);
    Path runLog = directory.resolve(scenario + "-run.log");
    assertEquals(0, run(Arrays.asList(executable.toString()), runLog, 30), () -> "See " + runLog);
    String runtimeOutput = new String(Files.readAllBytes(runLog), UTF_8);
    assertTrue(runtimeOutput.contains("Native application started"), () -> "See " + runLog);
    assertFalse(
        runtimeOutput.contains("Build-time MXBean registered"),
        "Registration must happen during image building, not application startup");
    return runtimeOutput;
  }

  private int run(List<String> command, Path log, long timeoutSeconds) throws Exception {
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(
          process.waitFor(timeoutSeconds, SECONDS),
          () -> "Timed out: " + command + "; log: " + log);
      return process.exitValue();
    } finally {
      process.destroyForcibly();
    }
  }
}
