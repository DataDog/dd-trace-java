package datadog.smoketest;

import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.smoketest.backend.EnabledIfDockerAvailable;
import datadog.smoketest.backend.TraceBackend;
import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * S8 pilot — ported from the Groovy {@code OpenTelemetrySmokeTest} onto the new Java smoke base.
 * The OpenTelemetry sample app runs under the agent (OTel enabled), emits its traces, and exits;
 * the test-agent backend must receive all of them. This is the first end-to-end exercise of the new
 * base with a real agent and a non-server (batch) app. The {@code .testAgent()} container backend
 * needs Docker, so the test is skipped when Docker is unavailable.
 */
@EnabledIfDockerAvailable
class OpenTelemetrySmokeTest {
  private static final int TIMEOUT_SECONDS = 30;

  @RegisterExtension
  static final SmokeApp app =
      SmokeApp.named("opentelemetry")
          .jar(System.getProperty("datadog.smoketest.shadowJar.path"))
          .jvmArgs("-Ddd.trace.otel.enabled=true")
          .backend(TraceBackend.testAgent())
          .notAServer()
          .workingDirectory(new File(System.getProperty("datadog.smoketest.builddir")))
          .build();

  @Test
  void receivesTraces() {
    // 1 @WithSpan-annotated span + 10 manual OpenTelemetry spans, each its own trace.
    app.traces().waitForTraceCount(11, TIMEOUT_SECONDS);

    // Telemetry is captured too and attributed to this test's session (S9): the agent emits at
    // least an app-started message, carrying the same session token as the traces.
    app.backend().telemetry().waitForCount(1, TIMEOUT_SECONDS);

    // The app then runs to completion and exits cleanly.
    app.assertCompletesWithValue(TIMEOUT_SECONDS, SECONDS, 0);
  }
}
