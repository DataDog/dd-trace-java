package datadog.trace.bootstrap;

import static datadog.trace.agent.test.IntegrationTestUtils.runOnSeparateJvm;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

class ProfilerEnvironmentCliTest {
  @TempDir Path temp;

  @TableTest({
    "Scenario                      | Positional directory | Configured directory",
    "positional overrides property | true                 | true                ",
    "profiling tempdir property    | false                | true                ",
    "default JVM temp directory    | false                | false               "
  })
  void usesProfilerTempDirectoryAndPreservesLegacyDirectory(
      boolean positionalDirectory, boolean configuredDirectory) throws Exception {
    Path jvmTemp = Files.createDirectory(temp.resolve("jvm-temp"));
    Path configuredTemp = Files.createDirectory(temp.resolve("configured-temp"));
    Path positionalTemp = Files.createDirectory(temp.resolve("positional-temp"));
    Path selectedTemp =
        positionalDirectory ? positionalTemp : configuredDirectory ? configuredTemp : jvmTemp;
    Path legacyDirectory = Files.createDirectory(selectedTemp.resolve("dd-profiler"));
    Path marker = legacyDirectory.resolve("keep.txt");
    Files.write(marker, "keep".getBytes(StandardCharsets.UTF_8));

    List<String> jvmArgs = new ArrayList<>();
    jvmArgs.add("-Djava.io.tmpdir=" + jvmTemp);
    jvmArgs.add("-Duser.name=profiler.cli/test user");
    if (configuredDirectory) {
      jvmArgs.add("-Ddd.profiling.tempdir=" + configuredTemp);
    }
    List<String> cliArgs = new ArrayList<>();
    cliArgs.add("checkProfilerEnv");
    if (positionalDirectory) {
      cliArgs.add(positionalTemp.toString());
    }
    String report = runCli(0, jvmArgs, cliArgs);

    Path expectedPrefix = selectedTemp.resolve("ddprof_profiler_cli_test_user_");
    assertTrue(
        Pattern.compile(
                Pattern.quote("Checking profiler temporary directory: " + expectedPrefix)
                    + "[0-9]+"
                    + Pattern.quote(File.separator + "pid_")
                    + "[0-9]+(?:\\r?\\n|$)")
            .matcher(report)
            .find(),
        report);
    assertTrue(report.contains("Profiler is ready to be used."), report);
    String hint = "! Make sure to add '-Ddd.profiling.tempdir=";
    assertEquals(positionalDirectory, report.contains(hint), report);
    if (positionalDirectory) {
      assertTrue(report.contains(hint + positionalTemp + "' to your JVM command line !"), report);
    }
    assertEquals("keep", new String(Files.readAllBytes(marker), StandardCharsets.UTF_8));
  }

  @Test
  void reportsMissingTempDirectory() throws Exception {
    Path missing = temp.resolve("missing");
    String report =
        runCli(
            1,
            singletonList("-Djava.io.tmpdir=" + temp),
            asList("checkProfilerEnv", missing.toString()));

    assertTrue(report.contains("Unable to obtain profiler temporary directory."), report);
    assertTrue(report.contains(missing.toString()), report);
    assertFalse(report.contains("! Make sure to add '-Ddd.profiling.tempdir="), report);
  }

  @Test
  void doesNotPrintHintForJvmTempDirectory() throws Exception {
    String report =
        runCli(
            0,
            singletonList("-Djava.io.tmpdir=" + temp),
            asList("checkProfilerEnv", temp + File.separator + "." + File.separator));

    assertTrue(report.contains("Profiler is ready to be used."), report);
    assertFalse(report.contains("! Make sure to add '-Ddd.profiling.tempdir="), report);
  }

  @Test
  void rejectsExtraArgumentsWithNonZeroExitCode() throws Exception {
    String report =
        runCli(
            1,
            singletonList("-Djava.io.tmpdir=" + temp),
            asList("checkProfilerEnv", temp.toString(), "extra"));

    assertTrue(report.contains("checkProfilerEnv accepts at most one temp directory"), report);
    assertFalse(report.contains("unknown option:"), report);
    assertFalse(report.contains("Checking profiler temporary directory:"), report);
  }

  private static String runCli(int expectedExitCode, List<String> jvmArgs, List<String> cliArgs)
      throws Exception {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    int exitCode;
    try (PrintStream stream = new PrintStream(output)) {
      exitCode = runOnSeparateJvm(AgentJar.class.getName(), jvmArgs, cliArgs, emptyMap(), stream);
    }
    String report = new String(output.toByteArray(), StandardCharsets.UTF_8);
    assertEquals(expectedExitCode, exitCode, report);
    return report;
  }
}
