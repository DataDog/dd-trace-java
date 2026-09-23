package datadog.smoketest;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
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

  private Process launch(ScopeDiagnosticsClient diagnostics, String mode) throws Exception {
    String classes =
        Paths.get(
                ScopeDiagnosticsTestApp.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI())
            .toString();
    return new ProcessBuilder(
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
        .redirectError(reports.resolve("child-" + mode + ".log").toFile())
        .start();
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
