package datadog.smoketest.concurrent;

import static datadog.smoketest.backend.AgentBackend.testAgent;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.smoketest.SmokeCliApp;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class VirtualThreadStartupSmokeTest {
  @RegisterExtension
  static final SmokeCliApp app =
      SmokeCliApp.named("virtual-thread-startup")
          .mainClass(
              "datadog.smoketest.concurrent.VirtualThreadStartup",
              System.getProperty("datadog.smoketest.shadowJar.path"))
          .jvmArgs(
              "-Djdk.virtualThreadScheduler.parallelism=1",
              "-Djdk.virtualThreadScheduler.maxPoolSize=1")
          .backend(testAgent())
          .skipTelemetryCheck()
          .build();

  @Test
  void startsVirtualThreadWithoutActiveContext() {
    app.assertCompletesWithValue(30, SECONDS, 0);
  }
}
