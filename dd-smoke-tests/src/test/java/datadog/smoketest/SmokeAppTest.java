package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.smoketest.backend.TraceBackend;
import datadog.smoketest.backend.Traces;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Exercises {@link SmokeApp}'s launch mechanics end-to-end against a trivial JVM app ({@link
 * TestServerApp}): port allocation + {@code ${app.httpPort}} substitution, process launch, HTTP
 * reachability, stdout capture, owned-backend lifecycle, and parameter injection. Runs without the
 * agent (mechanics only); a real agent + instrumented app + trace assertions land in the S8 pilot.
 */
class SmokeAppTest {

  @RegisterExtension
  static final SmokeApp app =
      SmokeApp.named("test-server")
          .mainClass("datadog.smoketest.TestServerApp")
          .args("--server.port=${app.httpPort}")
          .backend(TraceBackend.mockAgent())
          .noAgent()
          .build();

  @Test
  void respondsOnTheAllocatedPort() {
    assertTrue(app.httpPort() > 0, "a port was allocated");
    // Reaching the app proves ${app.httpPort} was substituted into the launch args.
    assertEquals(200, app.get("/hello"), "app serves HTTP on the substituted port");
  }

  @Test
  void capturesApplicationLogOutput() {
    app.get("/ping");
    assertTrue(
        app.awaitLogLine(line -> line.contains("REQUEST GET /ping")),
        "app stdout is captured during the test");
  }

  @Test
  void ownsAndStartsItsBackend() {
    assertNotNull(app.backend().url(), "the owned backend was started before the app");
    assertTrue(app.traces().getTraces().isEmpty(), "no traces arrive without an agent");
  }

  @Test
  void injectsHandlesByType(SmokeApp injected, Traces traces) {
    assertSame(app, injected, "SmokeApp resolved by type");
    assertNotNull(traces, "Traces resolved by type");
  }
}
