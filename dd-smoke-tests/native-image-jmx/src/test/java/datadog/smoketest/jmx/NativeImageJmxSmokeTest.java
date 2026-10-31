package datadog.smoketest.jmx;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class NativeImageJmxSmokeTest {
  @Test
  void buildsWithAgentAfterBuildTimeMxBeanRegistration() throws Exception {
    Path directory = newBuildDirectory();

    // The control must pass first: the fixture alone must not retain the builder's MBean server.
    buildAndRun(directory, false, JmxApplication.class);
    buildAndRun(directory, true, JmxApplication.class);
  }

  @Test
  void supportsRuntimeJmxAfterBuildTimeMxBeanRegistration() throws Exception {
    assertTrue(
        buildAndRun(newBuildDirectory(), true, RuntimeJmxApplication.class)
            .contains("Runtime JMX metrics available"));
  }

  private Path newBuildDirectory() throws Exception {
    Path buildDirectory = Paths.get(System.getProperty("datadog.smoketest.builddir"));
    Files.createDirectories(buildDirectory);
    return Files.createTempDirectory(buildDirectory, "native-image-jmx-");
  }

  private String buildAndRun(Path directory, boolean withAgent, Class<?> application)
      throws Exception {
    String name = withAgent ? "with-agent" : "without-agent";
    Path executable = directory.resolve(name);
    Path buildLog = directory.resolve(name + "-build.log");
    List<String> command =
        new ArrayList<>(
            Arrays.asList(
                Paths.get(System.getProperty("java.home"), "bin", "native-image").toString(),
                "-O0",
                "--no-fallback",
                "--parallelism=4",
                "-J-Xmx4g",
                // Match Quarkus's default; the tracer supplies its own runtime exceptions.
                "--initialize-at-build-time",
                "-cp",
                new File(
                        JmxApplication.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .getAbsolutePath()));
    if (withAgent) {
      command.add("-J-javaagent:" + System.getProperty("datadog.smoketest.agent.shadowJar.path"));
    }
    command.add(application.getName());
    command.add(executable.toString());

    int exitCode = run(command, buildLog, MINUTES.toSeconds(10));
    String output = new String(Files.readAllBytes(buildLog), UTF_8);
    assertTrue(
        output.contains("Build-time MXBean registered"), () -> "Fixture did not run: " + buildLog);
    assertEquals(
        0,
        exitCode,
        () ->
            "Native-image build failed ("
                + name
                + "). It reproduces when the agent retains the builder's JmxMBeanServer. Log: "
                + buildLog
                + "\n"
                + output);

    Path runLog = directory.resolve(name + "-run.log");
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
