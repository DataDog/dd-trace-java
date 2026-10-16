package datadog.smoketest;

import static datadog.trace.agent.test.scopediag.ScopeDiagnosticsCheck.LEAKED;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.scopediag.ScopeDiagnosticsCheck;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

@Timeout(60)
class ScopeDiagnosticsAgentTest {
  @TempDir Path reports;

  @Test
  void observesActualShadedTracerInQuickCli() throws Exception {
    ScopeDiagnosticsClient diagnostics = new ScopeDiagnosticsClient(reports, "resolved", true);
    Process child = launch(diagnostics, "resolved");
    try {
      diagnostics.awaitReady(child);
      assertTrue(child.waitFor(30, SECONDS));
      assertEquals(0, child.exitValue());
      diagnostics.verifyCompleted(child);
      Properties result = result(diagnostics.directory().resolve("final"));
      assertTrue(Integer.parseInt(result.getProperty("eventCount")) > 0);
      assertTrue(result.getProperty("timeline").contains("diagnostic-resolved"));
    } finally {
      child.destroyForcibly();
    }
  }

  @Test
  void startupFileOperationsDoNotInitializeDefaultFileSystem() throws Exception {
    ScopeDiagnosticsClient diagnostics = new ScopeDiagnosticsClient(reports, "filesystem", true);
    ProcessBuilder builder = processBuilder(diagnostics, "resolved");
    // Isolate the companion's file I/O from production-agent startup.
    builder.command().remove(1);
    builder
        .command()
        .add(
            1,
            "-Djava.nio.file.spi.DefaultFileSystemProvider=datadog.smoketest.MissingFileSystemProvider");
    Process child = builder.start();
    try {
      AssertionError failure =
          assertThrows(AssertionError.class, () -> diagnostics.awaitReady(child));
      assertTrue(
          failure.getMessage().replace('/', '.').contains("datadog.trace.bootstrap.Agent"),
          failure.getMessage());
      assertTrue(child.waitFor(30, SECONDS));
      assertTrue(child.exitValue() != 0);
      Properties report = result(diagnostics.directory().resolve("error"));
      assertEquals("error", report.getProperty("status"));
    } finally {
      child.destroyForcibly();
    }
  }

  @Test
  void leakedContinuationFailsWithCaptureSite() throws Exception {
    ScopeDiagnosticsClient diagnostics = new ScopeDiagnosticsClient(reports, "leaked", true);
    Process child = launch(diagnostics, "leak");
    try {
      diagnostics.awaitReady(child);
      assertTrue(child.waitFor(30, SECONDS));
      AssertionError failure =
          assertThrows(AssertionError.class, () -> diagnostics.verifyCompleted(child));
      assertTrue(failure.getMessage().contains("LEAKED"), failure.getMessage());
      Properties result = result(diagnostics.directory().resolve("final"));
      assertTrue(
          result.getProperty("timeline").contains("ScopeDiagnosticsTestApp"),
          result.getProperty("timeline"));
    } finally {
      child.destroyForcibly();
    }
  }

  @Test
  void serverWindowsObserveWorkWithoutRetainingPreviousLeak() throws Exception {
    ScopeDiagnosticsClient diagnostics = new ScopeDiagnosticsClient(reports, "server", false);
    Process child = launch(diagnostics, "server");
    try (Writer input = new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8);
        BufferedReader output =
            new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
      diagnostics.awaitReady(child);
      diagnostics.start(child);
      exercise(input, output, "leak");
      assertThrows(AssertionError.class, () -> diagnostics.finish(child));
      diagnostics.start(child);
      exercise(input, output, "resolved");
      diagnostics.finish(child);
      Properties second = result(diagnostics.directory().resolve("response-4"));
      assertTrue(Integer.parseInt(second.getProperty("eventCount")) > 0);
      assertTrue(second.getProperty("timeline").contains("diagnostic-resolved"));
      input.write("exit\n");
      input.flush();
      assertTrue(child.waitFor(30, SECONDS));
    } finally {
      child.destroyForcibly();
    }
  }

  @TableTest({
    "Scenario                           | Checks                 | Mode              | Status  ",
    "excluded leak remains recorded     | [LEAKED]               | leak              | ok      ",
    "double finish remains enforced     | [LEAKED]               | double            | problems",
    "unclosed scope remains enforced    | [LEAKED]               | unclosed          | problems",
    "multiple checks can be excluded    | [LEAKED, NEVER_CLOSED] | leak-and-unclosed | ok      ",
    "excluding leak does not hide scope | [LEAKED]               | leak-and-unclosed | problems"
  })
  void agentConfigurationSelectsEnforcement(
      ScopeDiagnosticsCheck[] checks, String mode, String status) throws Exception {
    ScopeDiagnosticsClient diagnostics =
        new ScopeDiagnosticsClient(reports, mode, true, "known test fixture", checks);
    Process child = launch(diagnostics, mode);
    try {
      diagnostics.awaitReady(child);
      assertTrue(child.waitFor(30, SECONDS));
      assertEquals(0, child.exitValue());
      if ("ok".equals(status)) {
        diagnostics.verifyCompleted(child);
      } else {
        assertThrows(AssertionError.class, () -> diagnostics.verifyCompleted(child));
      }
      Properties result = result(diagnostics.directory().resolve("final"));
      assertEquals(status, result.getProperty("status"), result.getProperty("detail"));
      assertEquals("known test fixture", result.getProperty("reason"));
      assertTrue(Integer.parseInt(result.getProperty("eventCount")) > 0);
      assertTrue(result.getProperty("timeline").contains("diagnostic-" + mode));
      if (mode.startsWith("leak")) {
        assertTrue(
            result.getProperty("detail").contains("Excluded findings (not enforced):\n  [LEAKED]"),
            result.getProperty("detail"));
      }
    } finally {
      child.destroyForcibly();
    }
  }

  @TableTest({
    "Scenario       | Checks                 | Reason  | Error                ",
    "missing reason | LEAKED                 | ''      | requires a reason    ",
    "unknown check  | MISSPELLED             | fixture | MISSPELLED           ",
    "trailing empty | 'LEAKED,'              | fixture | ScopeDiagnosticsCheck",
    "leading empty  | ',LEAKED'              | fixture | ScopeDiagnosticsCheck",
    "middle empty   | 'LEAKED,,NEVER_CLOSED' | fixture | ScopeDiagnosticsCheck"
  })
  void invalidExclusionsFailInstallation(String checks, String reason, String error)
      throws Exception {
    ScopeDiagnosticsClient diagnostics = new ScopeDiagnosticsClient(reports, "invalid", true);
    Path configFile = diagnostics.directory().resolve("config");
    Properties config = result(configFile);
    config.setProperty("disabledChecks", checks);
    config.setProperty("reason", reason);
    try (OutputStream output = Files.newOutputStream(configFile)) {
      config.store(output, null);
    }
    Process child = launch(diagnostics, "resolved");
    try {
      AssertionError failure =
          assertThrows(AssertionError.class, () -> diagnostics.awaitReady(child));
      assertTrue(failure.getMessage().contains(error), failure.getMessage());
      assertTrue(child.waitFor(30, SECONDS));
      assertTrue(child.exitValue() != 0);
    } finally {
      child.destroyForcibly();
    }
  }

  @Test
  void serverExclusionsApplyToEveryWindowWithoutHidingOtherViolations() throws Exception {
    ScopeDiagnosticsClient diagnostics =
        new ScopeDiagnosticsClient(
            reports,
            "selective-server",
            false,
            " Known fixture & cleanup=expected; café + path\t\\\n ",
            LEAKED);
    Process child = launch(diagnostics, "server");
    try (Writer input = new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8);
        BufferedReader output =
            new BufferedReader(
                new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
      diagnostics.awaitReady(child);
      diagnostics.start(child);
      exercise(input, output, "leak");
      diagnostics.finish(child);
      Properties first = result(diagnostics.directory().resolve("response-2"));
      assertTrue(first.getProperty("detail").contains("LEAKED"));
      assertEquals(
          " Known fixture & cleanup=expected; café + path\t\\\n ", first.getProperty("reason"));
      assertTrue(first.getProperty("timeline").contains("diagnostic-leak"));
      diagnostics.start(child);
      exercise(input, output, "double");
      AssertionError failure = assertThrows(AssertionError.class, () -> diagnostics.finish(child));
      assertTrue(failure.getMessage().contains("DOUBLE_FINISH"), failure.getMessage());
      input.write("exit\n");
      input.flush();
      assertTrue(child.waitFor(30, SECONDS));
    } finally {
      child.destroyForcibly();
    }
  }

  private Process launch(ScopeDiagnosticsClient diagnostics, String mode) throws Exception {
    return processBuilder(diagnostics, mode).start();
  }

  private ProcessBuilder processBuilder(ScopeDiagnosticsClient diagnostics, String mode)
      throws Exception {
    String classes =
        Paths.get(
                ScopeDiagnosticsTestApp.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI())
            .toString();
    ProcessBuilder builder =
        new ProcessBuilder(
                Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-javaagent:" + System.getProperty("datadog.smoketest.agent.shadowJar.path"),
                diagnostics.javaAgentArgument(),
                "-Ddd.trace.enabled=true",
                "-Ddd.telemetry.enabled=false",
                "-Ddd.remote_config.enabled=false",
                "-Ddd.trace.startup.logs=false",
                "-cp",
                classes,
                ScopeDiagnosticsTestApp.class.getName(),
                mode)
            .redirectError(reports.resolve("child-" + mode + ".log").toFile());
    // Keep hostname discovery from starting an unrelated subprocess in these short fixtures.
    builder.environment().put("HOSTNAME", "scope-diagnostics-test");
    builder.environment().put("COMPUTERNAME", "scope-diagnostics-test");
    return builder;
  }

  private static void exercise(Writer input, BufferedReader output, String action)
      throws Exception {
    input.write(action + "\n");
    input.flush();
    assertEquals("DONE", output.readLine());
  }

  private static Properties result(Path path) throws Exception {
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(path)) {
      properties.load(input);
    }
    return properties;
  }
}
