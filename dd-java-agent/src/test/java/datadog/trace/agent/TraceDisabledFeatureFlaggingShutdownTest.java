package datadog.trace.agent;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.IntegrationTestUtils;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import jvmbootstraptest.AgentLoadedChecker;
import jvmbootstraptest.FeatureFlaggingShutdownProbe;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void activatedProviderFlushesBothEventTypesAtJvmExit(boolean tracing) throws Exception {
    final LinkedBlockingQueue<RecordedRequest> events = new LinkedBlockingQueue<>();
    try (MockWebServer server = new MockWebServer()) {
      server.setDispatcher(
          new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
              if (request.getPath().equals("/info")) {
                return new MockResponse()
                    .setBody(
                        "{\"endpoints\":[\"v0.4/traces\",\"v0.5/traces\",\"evp_proxy/v4/\"],"
                            + "\"evp_proxy_allowed_headers\":[\"DD-EVP-ORIGIN\",\"DD-EVP-ORIGIN-VERSION\"]}");
              }
              if (request.getPath().startsWith("/evp_proxy/")) {
                events.add(request);
                return new MockResponse().setResponseCode(202).setBody("{}");
              }
              return new MockResponse().setResponseCode(404);
            }
          });
      server.start();
      final List<String> options = options(tracing);
      options.add("-Ddd.trace.agent.url=" + server.url("/"));
      options.add("-Ddd.feature.flags.configuration.source=agentless");
      options.add(
          "-Ddd.feature.flags.configuration.source.agentless.base.url=" + server.url("/config"));
      final String logs = run(FeatureFlaggingShutdownProbe.class, options);
      assertTrue(logs.contains("EXIT_WITH_QUEUED_FEATURE_FLAG_EVENTS"), logs);
      assertEquals(tracing, logs.contains("Shutting down agent"), logs);
      assertTrue(logs.contains("Feature Flagging system stopped"), logs);

      final List<String> paths = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        final RecordedRequest event = events.poll(1, TimeUnit.SECONDS);
        assertNotNull(event, logs);
        paths.add(event.getPath());
        assertEquals("POST", event.getMethod());
        assertNull(event.getHeader("DD-API-KEY"));
        assertTrue(event.getBody().readUtf8().contains("shutdown-flag"));
      }
      assertTrue(paths.contains("/evp_proxy/v4/api/v2/exposures"), paths.toString());
      assertTrue(paths.contains("/evp_proxy/v4/api/v2/flagevaluation"), paths.toString());
      assertEquals(0, events.size(), "Shutdown must not duplicate either batch");
    }
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
