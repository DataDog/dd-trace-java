package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.smoketest.backend.AgentBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Exercises {@link SmokeServerApp}'s launch mechanics end-to-end against a trivial JVM app ({@link
 * TestServerApp}): port allocation + {@code ${app.httpPort}} substitution, process launch, HTTP
 * reachability, stdout capture, and owned-backend lifecycle. Runs without the agent (mechanics
 * only); a real agent + instrumented app + trace assertions land in the S8 pilot.
 */
class SmokeServerAppTest {

  @RegisterExtension
  static final SmokeServerApp app =
      SmokeServerApp.named("test-server")
          .mainClass("datadog.smoketest.TestServerApp")
          .placeholder("marker", () -> "resolved-at-launch")
          .args("--server.port=${app.httpPort}", "--marker=${marker}")
          .backend(AgentBackend.mockAgent())
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
  void substitutesCustomPlaceholderAtLaunch() {
    app.get("/ping");
    // The app echoes its --marker launch arg; seeing the resolved value proves the custom ${marker}
    // placeholder was substituted from its Supplier when the app launched.
    assertTrue(
        app.awaitLogLine(line -> line.contains("marker=resolved-at-launch")),
        "custom placeholder was substituted into the launch args");
  }

  @Test
  void ownsAndStartsItsBackend() {
    assertNotNull(app.backend().url(), "the owned backend was started before the app");
    assertTrue(app.traces().getTraces().isEmpty(), "no traces arrive without an agent");
  }
}
