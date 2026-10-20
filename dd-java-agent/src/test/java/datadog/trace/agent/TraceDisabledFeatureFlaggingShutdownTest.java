package datadog.trace.agent;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.agent.test.IntegrationTestUtils;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jvmbootstraptest.AgentLoadedChecker;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// TODO Test that the dd-openfeature SDK flushes its pending events at JVM exit through the agent,
// with and without tracing, in the OpenFeature smoke tests.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TraceDisabledFeatureFlaggingShutdownTest {
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void unusedFeatureFlagsDoNotEnableWholeAgentShutdown(boolean explicitlyEnabled) throws Exception {
    final List<String> options = options(false);
    if (explicitlyEnabled) {
      options.add("-Ddd.feature.flags.enabled=true");
    }
    final String logs = run(AgentLoadedChecker.class, options);
    assertFalse(logs.contains("Shutting down agent"), logs);
    assertFalse(logs.contains("Feature Flagging system stopped"), logs);
  }

  private static List<String> options(boolean tracing) {
    return new ArrayList<>(
        Arrays.asList(
            "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
            "-Ddd.trace.enabled=" + tracing,
            "-Ddd.jmxfetch.enabled=false",
            "-Ddd.profiling.enabled=false",
            "-Ddd.appsec.enabled=false",
            "-Ddd.remote_config.enabled=false",
            "-Ddd.telemetry.enabled=false"));
  }

  private static String run(Class<?> main, List<String> options) throws Exception {
    try (ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8.name())) {
      final int exit =
          IntegrationTestUtils.runOnSeparateJvm(
              main.getName(), options, emptyList(), singletonMap("DD_API_KEY", ""), stream);
      final String logs = output.toString(StandardCharsets.UTF_8.name());
      assertEquals(0, exit, logs);
      return logs;
    }
  }
}
