package datadog.smoketest;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.smoketest.backend.AgentBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(60)
class ScopeDiagnosticsAppTest {
  @Test
  void cliHarnessChecksResolvedContinuationByDefault() throws Exception {
    SmokeCliApp app = app("resolved");
    try {
      app.beforeAll(null);
      app.beforeEach(null);
      app.assertCompletesWithValue(30, SECONDS, 0);
      app.afterEach(null);
    } finally {
      app.afterAll(null);
    }
  }

  @Test
  void cliHarnessFailsOnLeakedContinuationByDefault() throws Exception {
    SmokeCliApp app = app("leak");
    try {
      app.beforeAll(null);
      app.beforeEach(null);
      AssertionError failure =
          assertThrows(AssertionError.class, () -> app.assertCompletesWithValue(30, SECONDS, 0));
      assertTrue(failure.getMessage().contains("LEAKED"), failure.getMessage());
    } finally {
      assertThrows(AssertionError.class, () -> app.afterAll(null));
    }
  }

  private static SmokeCliApp app(String mode) {
    return SmokeCliApp.named("scope-" + mode)
        .mainClass(ScopeDiagnosticsTestApp.class)
        .args(mode)
        .backend(AgentBackend.mockAgent())
        .jvmArgs("-Ddd.telemetry.enabled=false", "-Ddd.remote_config.enabled=false")
        .skipTelemetryCheck()
        .build();
  }
}
