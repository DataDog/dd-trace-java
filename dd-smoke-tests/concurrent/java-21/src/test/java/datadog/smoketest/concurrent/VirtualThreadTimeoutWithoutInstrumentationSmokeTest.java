package datadog.smoketest.concurrent;

import static datadog.smoketest.backend.AgentBackend.testAgent;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.smoketest.SmokeCliApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class VirtualThreadTimeoutWithoutInstrumentationSmokeTest {
  @RegisterExtension
  static final SmokeCliApp app =
      SmokeCliApp.named("virtual-thread-timeout-without-instrumentation")
          .mainClass(
              "datadog.smoketest.concurrent.VirtualThreadTimeoutStartup",
              System.getProperty("datadog.smoketest.shadowJar.path"))
          .jvmArgs(
              "-Ddd.integration.virtual-thread.enabled=false",
              "-Ddd.integration.fjp.enabled=true",
              "-Djdk.virtualThreadScheduler.parallelism=1",
              "-Djdk.virtualThreadScheduler.maxPoolSize=1")
          .backend(testAgent())
          .skipTelemetryCheck()
          .build();

  @Test
  void coldStartVirtualThreadCanParkAndResumeWithVirtualThreadInstrumentationDisabled() {
    app.assertCompletesWithValue(30, SECONDS, 0);
  }
}
