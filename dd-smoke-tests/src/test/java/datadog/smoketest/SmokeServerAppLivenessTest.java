package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.smoketest.backend.AgentBackend;
import org.junit.jupiter.api.Test;

/**
 * Verifies a {@link SmokeServerApp} whose server dies while a test runs fails that test, including
 * during the last (or only) method, after which no later callback re-checks liveness and teardown
 * accepts the already-exited process. The app lifecycle is driven by hand rather than through
 * {@code @RegisterExtension}, since the expected failure would otherwise fail this very test.
 */
class SmokeServerAppLivenessTest {

  @Test
  void failsWhenTheServerDiesDuringATest() throws Exception {
    SmokeServerApp app =
        SmokeServerApp.named("dying-server")
            .mainClass("datadog.smoketest.TestServerApp")
            .args("--server.port=${app.httpPort}")
            .backend(AgentBackend.mockAgent())
            .noAgent()
            .build();

    app.beforeAll(null);
    try {
      Process process = app.process();
      process.destroy();
      process.waitFor();

      IllegalStateException failure =
          assertThrows(IllegalStateException.class, () -> app.afterEach(null));
      assertTrue(failure.getMessage().contains("at the end of a test"), failure.getMessage());
    } finally {
      app.afterAll(null);
    }
  }
}
