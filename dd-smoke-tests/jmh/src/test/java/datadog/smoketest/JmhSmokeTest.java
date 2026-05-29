package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.civisibility.CiVisibilitySmokeTest;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JmhSmokeTest extends CiVisibilitySmokeTest {

  private static final String TEST_SERVICE_NAME = "test-jmh-service";
  private static final int PROCESS_TIMEOUT_SECS = 120;

  private static final String JMH_CORE_JAR =
      System.getProperty("datadog.smoketest.jmh.core.jar.path");

  static final MockBackend mockBackend = new MockBackend();

  @BeforeEach
  void resetMockBackend() {
    mockBackend.reset();
  }

  @AfterAll
  static void closeMockBackend() throws Exception {
    mockBackend.close();
  }

  @Test
  void testBenchmarkSpansAreEmitted() throws Exception {
    Map<String, String> agentArgs = new HashMap<>();
    agentArgs.put(CiVisibilityConfig.CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED, "false");
    agentArgs.put(GeneralConfig.AGENTLESS_LOG_SUBMISSION_URL, mockBackend.getIntakeUrl());

    int exitCode = runBenchmark(agentArgs);
    assertEquals(0, exitCode, "JMH process should exit cleanly");

    // 4 events: test_session_end, test_module_end, test_suite_end, test (benchmark method)
    List<Map<String, Object>> events = mockBackend.waitForEvents(4);
    assertEquals(4, events.size());

    Map<String, Object> testEvent = findEvent(events, "test");
    assertNotNull(testEvent, "Expected a test span for the benchmark method");

    @SuppressWarnings("unchecked")
    Map<String, Object> meta =
        (Map<String, Object>) ((Map<String, Object>) testEvent.get("content")).get("meta");
    @SuppressWarnings("unchecked")
    Map<String, Object> metrics =
        (Map<String, Object>) ((Map<String, Object>) testEvent.get("content")).get("metrics");

    assertEquals("jmh", meta.get("test.framework"));
    assertEquals("measure", meta.get("test.name"));
    assertEquals("datadog.smoketest.SmokeTestBenchmark", meta.get("test.suite"));
    assertEquals("pass", meta.get("test.status"));
    assertEquals("avgt", meta.get("benchmark.run.mode"));
    assertEquals("ns/op", meta.get("benchmark.unit"));

    assertNotNull(metrics.get("benchmark.value"), "benchmark.value should be present");
    assertTrue(
        ((Number) metrics.get("benchmark.value")).doubleValue() > 0,
        "benchmark.value should be positive");
  }

  private int runBenchmark(Map<String, String> additionalAgentArgs) throws Exception {
    assertTrue(new File(JMH_CORE_JAR).isFile(), "JMH core jar not found: " + JMH_CORE_JAR);

    String classpath = buildClasspath();

    List<String> command = new ArrayList<>();
    command.add(javaPath());
    command.addAll(
        buildJvmArguments(mockBackend.getIntakeUrl(), TEST_SERVICE_NAME, additionalAgentArgs));
    Collections.addAll(command, "-cp", classpath);
    command.add("org.openjdk.jmh.Main");
    command.add("datadog.smoketest.SmokeTestBenchmark.*");
    Collections.addAll(command, "-f", "0"); // run in-process (no forking)
    Collections.addAll(command, "-wi", "1"); // 1 warmup iteration
    Collections.addAll(command, "-i", "1"); // 1 measurement iteration
    Collections.addAll(command, "-w", "1ms"); // warmup duration
    Collections.addAll(command, "-r", "1ms"); // measurement duration
    Collections.addAll(command, "-jvmArgs", "-Djmh.ignoreLock=true");

    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.redirectErrorStream(true);
    processBuilder.environment().put("DD_API_KEY", "01234567890abcdef123456789ABCDEF");
    Process p = processBuilder.start();

    // consume output to avoid blocking
    final java.io.InputStream stdout = p.getInputStream();
    Thread outputConsumer =
        new Thread() {
          @Override
          public void run() {
            try {
              byte[] buf = new byte[1024];
              int n;
              while ((n = stdout.read(buf)) != -1) {
                System.out.write(buf, 0, n);
              }
            } catch (Exception ignored) {
            }
          }
        };
    outputConsumer.setDaemon(true);
    outputConsumer.start();

    if (!p.waitFor(PROCESS_TIMEOUT_SECS, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new TimeoutException("JMH process timed out after " + PROCESS_TIMEOUT_SECS + "s");
    }
    return p.exitValue();
  }

  private static String buildClasspath() {
    // Use the current test process classpath — it includes jmh-core, SmokeTestBenchmark, and its
    // META-INF/BenchmarkList (generated by the annotation processor at compile time)
    return System.getProperty("java.class.path");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> findEvent(List<Map<String, Object>> events, String type) {
    return events.stream().filter(e -> type.equals(e.get("type"))).findFirst().orElse(null);
  }
}
