package datadog.trace.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.IntegrationTestUtils;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import jvmbootstraptest.AgentLoadedChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class TraceDisabledFeatureFlaggingShutdownTest {
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void featureFlaggingIsStoppedByTheRealAgentWhenTracingIsDisabled() throws Exception {
    try (ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream printStream = new PrintStream(output, true, StandardCharsets.UTF_8.name())) {
      int exitCode =
          IntegrationTestUtils.runOnSeparateJvm(
              AgentLoadedChecker.class.getName(),
              Arrays.asList(
                  "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
                  "-Ddd.trace.enabled=false",
                  "-Ddd.feature.flags.enabled=true",
                  "-Ddd.feature.flags.configuration.source=agentless",
                  "-Ddd.jmxfetch.enabled=false",
                  "-Ddd.profiling.enabled=false",
                  "-Ddd.remote_config.enabled=false",
                  "-Ddd.telemetry.enabled=false"),
              Collections.emptyList(),
              Collections.emptyMap(),
              printStream);

      String logs = output.toString(StandardCharsets.UTF_8.name());
      assertEquals(0, exitCode);
      assertTrue(logs.contains("Shutting down agent"));
      assertTrue(logs.contains("Feature Flagging system stopped"));
    }
  }
}
