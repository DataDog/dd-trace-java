package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.smoketest.backend.AgentBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Exercises {@link SmokeCliApp}: a one-shot batch app ({@link TestCliApp}) produces all its output
 * at start-up, so its captured logs must survive into the test body rather than be cleared per test
 * — otherwise a suite asserting start-up diagnostics would flake or always time out. Runs without
 * the agent (launch/log-capture mechanics only).
 */
class SmokeCliAppTest {

  @RegisterExtension
  static final SmokeCliApp app =
      SmokeCliApp.named("test-cli")
          .mainClass("datadog.smoketest.TestCliApp")
          .backend(AgentBackend.mockAgent())
          .noAgent()
          .build();

  @Test
  void retainsStartupLogsForAssertion() {
    // The marker was printed during beforeAll; per-test log clearing (which a server does) must not
    // apply to a one-shot CLI app, so awaitLogLine still finds it here.
    assertTrue(
        app.awaitLogLine(line -> line.contains("CLI-STARTUP-MARKER")),
        "one-shot start-up output is retained for the test to assert on");
  }
}
