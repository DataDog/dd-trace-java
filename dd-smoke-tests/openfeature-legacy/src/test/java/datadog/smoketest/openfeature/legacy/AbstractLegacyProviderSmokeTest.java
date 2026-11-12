package datadog.smoketest.openfeature.legacy;

import static datadog.smoketest.backend.AgentBackend.testAgent;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.smoketest.SmokeCliApp;
import org.junit.jupiter.api.Test;

/** Checks a released provider returns the caller defaults instead of linking the removed API. */
abstract class AbstractLegacyProviderSmokeTest {
  static SmokeCliApp.Builder legacyProviderApp(final String name) {
    return SmokeCliApp.named(name)
        .jar(System.getProperty("datadog.smoketest.shadowJar.path"))
        .backend(testAgent())
        // The evaluations report the ERROR reason of the caller defaults.
        .allowedErrorLogs("EVALUATION ")
        // The app may exit before sending telemetry.
        .skipTelemetryCheck();
  }

  abstract SmokeCliApp app();

  @Test
  void returnsTheCallerDefaults() {
    final SmokeCliApp app = app();
    app.assertCompletesWithValue(30, SECONDS, 0);

    assertLogged(app, "PROVIDER READY");
    assertLogged(app, "PROVIDER_HOOKS 0");
    for (final String type : asList("boolean", "string", "integer", "double", "object")) {
      assertLogged(
          app,
          "EVALUATION "
              + type
              + " default=true reason=ERROR error=PROVIDER_NOT_READY variant=null");
    }
    assertTrue(
        app.waitForLogLine(line ->
            line.contains("WARN datadog.trace.api.openfeature.Provider")
                && line.contains("incompatible with the Datadog Java agent")),
        "the agent warns to upgrade the provider");
  }

  private static void assertLogged(final SmokeCliApp app, final String expected) {
    assertTrue(app.waitForLogLine(expected::equals), "the app logged: " + expected);
  }
}
