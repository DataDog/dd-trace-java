package datadog.smoketest;

import static datadog.trace.agent.test.scopediag.ScopeDiagnosticsCheck.LEAKED;
import static datadog.trace.agent.test.scopediag.ScopeDiagnosticsCheck.NEVER_CLOSED;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.scopediag.ScopeDiagnosticsCheck;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopeDiagnosticsClientTest {
  @TempDir Path temporary;

  @Test
  void serverWindowsUseDistinctSequences() throws Exception {
    ScopeDiagnosticsClient client = client(false);
    write(client, "ready", "protocol", "1");
    client.awaitReady(new StubProcess(true));
    response(client, "response-1", 1, "ok");
    client.start(new StubProcess(true));
    assertThrows(AssertionError.class, () -> client.start(new StubProcess(true)));
    response(client, "response-2", 2, "ok");
    client.finish(new StubProcess(true));
    response(client, "response-3", 3, "ok");
    client.start(new StubProcess(true));
    assertEquals("3", read(client, "command").getProperty("seq"));
    assertEquals("start", read(client, "command").getProperty("action"));
  }

  @Test
  void quickCliExitRetainsItsFinalReport() throws Exception {
    ScopeDiagnosticsClient client = client(true);
    write(client, "ready", "protocol", "1");
    response(client, "final", 0, "ok");
    StubProcess exited = new StubProcess(false);
    client.awaitReady(exited);
    client.start(exited);
    client.verifyCompleted(exited);
    assertDoesNotThrow(() -> client.verifyCompleted(exited));
    assertTrue(Files.notExists(client.directory().resolve("command")));
  }

  @Test
  void liveCliCanBeCheckedBeforeDestruction() throws Exception {
    ScopeDiagnosticsClient client = client(true);
    response(client, "response-1", 1, "ok");
    client.finish(new StubProcess(true));
    assertEquals("finish", read(client, "command").getProperty("action"));
    assertDoesNotThrow(() -> client.verifyCompleted(new StubProcess(false)));
  }

  @Test
  void cliExitRacingFinishUsesFinalReport() throws Exception {
    ScopeDiagnosticsClient client = client(true);
    response(client, "final", 0, "ok");
    client.finish(new StubProcess(true, false));
    assertEquals("finish", read(client, "command").getProperty("action"));
    assertDoesNotThrow(() -> client.verifyCompleted(new StubProcess(false)));
  }

  @Test
  void cliExitCannotHideAnInvalidFinishResponse() throws Exception {
    ScopeDiagnosticsClient client = client(true);
    response(client, "final", 0, "ok");
    response(client, "response-1", 0, "ok");
    AssertionError error =
        assertThrows(AssertionError.class, () -> client.finish(new StubProcess(true, false)));
    assertTrue(error.getMessage().contains("incorrect sequence"));
  }

  @Test
  void cliExitCannotHideReportedProblems() throws Exception {
    ScopeDiagnosticsClient client = client(true);
    response(client, "final", 0, "ok");
    response(client, "response-1", 1, "problems");
    AssertionError error =
        assertThrows(AssertionError.class, () -> client.finish(new StubProcess(true, false)));
    assertTrue(error.getMessage().contains("test timeline"));
  }

  @Test
  void cliExitFallbackStillValidatesFinalSequence() throws Exception {
    ScopeDiagnosticsClient client = client(true);
    response(client, "final", 1, "ok");
    AssertionError error =
        assertThrows(AssertionError.class, () -> client.finish(new StubProcess(true, false)));
    assertTrue(error.getMessage().contains("incorrect sequence"));
  }

  @Test
  void failedFinalReportIsConsumedOnce() throws Exception {
    ScopeDiagnosticsClient client = client(true);
    response(client, "final", 0, "problems");
    assertThrows(AssertionError.class, () -> client.verifyCompleted(new StubProcess(false)));
    assertDoesNotThrow(() -> client.finish(new StubProcess(false)));
    assertDoesNotThrow(() -> client.verifyCompleted(new StubProcess(false)));
  }

  @Test
  void invalidLiveFinishResponseIsConsumedOnce() throws Exception {
    ScopeDiagnosticsClient client = client(true);
    response(client, "response-1", 7, "ok");
    assertThrows(AssertionError.class, () -> client.finish(new StubProcess(true)));
    assertDoesNotThrow(() -> client.finish(new StubProcess(true)));
    assertDoesNotThrow(() -> client.verifyCompleted(new StubProcess(false)));
    assertEquals("1", read(client, "command").getProperty("seq"));
  }

  @Test
  void malformedFinalReportIsConsumedOnce() throws Exception {
    ScopeDiagnosticsClient client = client(true);
    write(client, "final", "protocol", "1", "seq", "0", "status", "ok");
    assertThrows(AssertionError.class, () -> client.verifyCompleted(new StubProcess(false)));
    assertDoesNotThrow(() -> client.verifyCompleted(new StubProcess(false)));
  }

  @Test
  void absentFinalReportCannotPass() throws Exception {
    ScopeDiagnosticsClient client = client(true);
    AssertionError error =
        assertThrows(AssertionError.class, () -> client.verifyCompleted(new StubProcess(false)));
    assertTrue(error.getMessage().contains("without diagnostic final"));
  }

  @Test
  void invalidProtocolAndIncompleteResponseCannotPass() throws Exception {
    ScopeDiagnosticsClient client = client(true);
    write(client, "ready", "protocol", "2");
    assertThrows(AssertionError.class, () -> client.awaitReady(new StubProcess(false)));
    write(client, "final", "protocol", "1", "seq", "0", "status", "ok");
    assertThrows(AssertionError.class, () -> client.verifyCompleted(new StubProcess(false)));
    ScopeDiagnosticsClient incorrectSequence = client(true);
    response(incorrectSequence, "final", 12, "ok");
    assertThrows(
        AssertionError.class, () -> incorrectSequence.verifyCompleted(new StubProcess(false)));
  }

  @Test
  void failuresCarryTimelineAndInstallationDetails() throws Exception {
    ScopeDiagnosticsClient client = client(true);
    response(client, "final", 0, "problems");
    AssertionError error =
        assertThrows(AssertionError.class, () -> client.verifyCompleted(new StubProcess(false)));
    assertTrue(error.getMessage().contains("test timeline"));
    write(client, "error", "detail", "installation failed");
    error = assertThrows(AssertionError.class, () -> client.awaitReady(new StubProcess(false)));
    assertTrue(error.getMessage().contains("installation failed"));
  }

  @Test
  void unresponsiveAgentTimesOut() throws Exception {
    ScopeDiagnosticsClient client = client(false);
    AssertionError error =
        assertThrows(AssertionError.class, () -> client.awaitReady(new StubProcess(true)));
    assertTrue(error.getMessage().contains("Timed out"));
  }

  @Test
  void optOutRequiresAnExplanation() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SmokeCliApp.named("cli").skipScopeContinuationCheck("  "));
    assertThrows(
        IllegalArgumentException.class,
        () -> SmokeServerApp.named("server").skipScopeContinuationCheck(null));
    assertDoesNotThrow(
        () -> SmokeCliApp.named("cli").skipScopeContinuationCheck("Expected Runtime.halt"));
  }

  @Test
  void configurationPreservesPathsAndReasons() throws Exception {
    ScopeDiagnosticsClient client =
        client(true, "fixture & cleanup=expected; café + path", LEAKED, NEVER_CLOSED);
    Properties config = read(client, "config");
    assertEquals("cli", config.getProperty("mode"));
    assertEquals("2000", config.getProperty("quiescenceMillis"));
    assertEquals("LEAKED,NEVER_CLOSED", config.getProperty("disabledChecks"));
    assertEquals("fixture & cleanup=expected; café + path", config.getProperty("reason"));
    assertTrue(client.javaAgentArgument().endsWith("=" + client.directory()));
    assertThrows(IllegalArgumentException.class, () -> client(true, "  ", LEAKED));
    assertThrows(IllegalArgumentException.class, () -> client(true, null, LEAKED));
    assertThrows(
        IllegalArgumentException.class,
        () -> client(true, "fixture", (ScopeDiagnosticsCheck) null));
  }

  @Test
  void quiescenceConfigurationIsValidatedAndWritten() throws Exception {
    String property = "datadog.smoketest.scopeDiagnostics.quiescenceMillis";
    String previous = System.getProperty(property);
    try {
      System.setProperty(property, "5000");
      assertEquals("5000", read(client(true), "config").getProperty("quiescenceMillis"));
      System.setProperty(property, "-1");
      assertThrows(IllegalArgumentException.class, () -> client(true));
      System.setProperty(property, "10001");
      assertThrows(IllegalArgumentException.class, () -> client(true));
      System.setProperty(property, "invalid");
      assertThrows(IllegalArgumentException.class, () -> client(true));
    } finally {
      if (previous == null) {
        System.clearProperty(property);
      } else {
        System.setProperty(property, previous);
      }
    }
  }

  @Test
  void builderRejectsInvalidOrContradictoryExclusions() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SmokeCliApp.named("cli").disableScopeContinuationChecks("", LEAKED));
    assertThrows(
        IllegalArgumentException.class,
        () -> SmokeServerApp.named("server").disableScopeContinuationChecks(null, LEAKED));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SmokeCliApp.named("cli")
                .disableScopeContinuationChecks(
                    "Known fixture",
                    (datadog.trace.agent.test.scopediag.ScopeDiagnosticsCheck) null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SmokeCliApp.named("cli")
                .skipScopeContinuationCheck("No premain")
                .disableScopeContinuationChecks("Known fixture", LEAKED)
                .build());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SmokeCliApp.named("cli")
                .disableScopeContinuationChecks("Known fixture", LEAKED)
                .skipScopeContinuationCheck("No premain")
                .build());
  }

  private ScopeDiagnosticsClient client(boolean cli) throws IOException {
    return client(cli, "");
  }

  private ScopeDiagnosticsClient client(boolean cli, String reason, ScopeDiagnosticsCheck... checks)
      throws IOException {
    Path jar = temporary.resolve("companion.jar");
    if (Files.notExists(jar)) {
      Files.createFile(jar);
    }
    return new ScopeDiagnosticsClient(temporary, "test app", cli, jar, 25, reason, checks);
  }

  private static void response(ScopeDiagnosticsClient client, String file, int seq, String status)
      throws IOException {
    write(
        client,
        file,
        "protocol",
        "1",
        "seq",
        Integer.toString(seq),
        "status",
        status,
        "detail",
        "test summary",
        "timeline",
        "test timeline",
        "eventCount",
        "0");
  }

  private static void write(ScopeDiagnosticsClient client, String file, String... entries)
      throws IOException {
    Properties properties = new Properties();
    for (int i = 0; i < entries.length; i += 2) {
      properties.setProperty(entries[i], entries[i + 1]);
    }
    try (OutputStream output = Files.newOutputStream(client.directory().resolve(file))) {
      properties.store(output, null);
    }
  }

  private static Properties read(ScopeDiagnosticsClient client, String file) throws IOException {
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(client.directory().resolve(file))) {
      properties.load(input);
    }
    return properties;
  }

  private static final class StubProcess extends Process {
    private final boolean[] alive;
    private int checks;

    StubProcess(boolean... alive) {
      this.alive = alive;
    }

    @Override
    public boolean isAlive() {
      return alive[Math.min(checks++, alive.length - 1)];
    }

    @Override
    public OutputStream getOutputStream() {
      return new ByteArrayOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public InputStream getErrorStream() {
      return getInputStream();
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {}
  }
}
