package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.api.DDTags;
import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import datadog.trace.test.util.NonRetryable;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

@NonRetryable
public class SpringCodeOriginIntegrationTest extends SpringBasedIntegrationTest {

  private boolean traceReceived;

  @Override
  protected ProcessBuilder createProcessBuilder(Path logFilePath, String... params) {
    List<String> commandParams = getDebuggerCommandParams();
    commandParams.add("-Ddd.trace.enabled=true"); // explicitly enable tracer
    commandParams.add("-Ddd.code.origin.for.spans.enabled=true");
    return ProcessBuilderHelper.createProcessBuilder(
        commandParams, logFilePath, getAppClass(), params);
  }

  @Test
  @DisplayName("testRegularController")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  void testRegularController() throws Exception {
    registerTraceListener(this::receiveGreetingTrace);
    String httpPort = startSpringApp(Collections.emptyList());
    sendRequest(httpPort, "/greeting"); // trigger CodeOriginProbe instrumentation
    waitForSpecificLogLine(
        logFilePath,
        "DEBUG com.datadog.debugger.agent.ConfigurationUpdater - Re-transformation done",
        Duration.ofMillis(100),
        Duration.ofSeconds(30)); // wait for instrumentation to be done
    sendRequest(httpPort, "/greeting"); // generate first span with tags
    processRequests(
        () -> traceReceived, () -> String.format("Timeout! traceReceived=%s", traceReceived));
  }

  @Test
  @DisplayName("testInterfacedController")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  void testInterfacedController() throws Exception {
    registerTraceListener(this::receiveProcessTrace);
    String httpPort = startSpringApp(Collections.emptyList());
    sendRequest(httpPort, "/process"); // trigger CodeOriginProbe instrumentation
    waitForSpecificLogLine(
        logFilePath,
        "DEBUG com.datadog.debugger.agent.ConfigurationUpdater - Re-transformation done",
        Duration.ofMillis(100),
        Duration.ofSeconds(30)); // wait for instrumentation to be done
    sendRequest(httpPort, "/process"); // generate first span with tags
    processRequests(
        () -> traceReceived, () -> String.format("Timeout! traceReceived=%s", traceReceived));
  }

  private void receiveGreetingTrace(DecodedTrace decodedTrace) {
    for (DecodedSpan span : decodedTrace.getSpans()) {
      if (span.getName().equals("spring.handler")
          && span.getResource().equals("WebController.greeting")
          && span.getMeta().containsKey(DDTags.DD_CODE_ORIGIN_FRAME_TYPE)) {
        assertEquals("entry", span.getMeta().get(DDTags.DD_CODE_ORIGIN_TYPE));
        assertEquals(
            "datadog.smoketest.debugger.controller.WebController",
            span.getMeta().get(DDTags.DD_CODE_ORIGIN_FRAME_TYPE));
        assertEquals("WebController.java", span.getMeta().get(DDTags.DD_CODE_ORIGIN_FRAME_FILE));
        assertEquals("10", span.getMeta().get(DDTags.DD_CODE_ORIGIN_FRAME_LINE));
        assertEquals("greeting", span.getMeta().get(DDTags.DD_CODE_ORIGIN_FRAME_METHOD));
        assertEquals("()", span.getMeta().get(DDTags.DD_CODE_ORIGIN_FRAME_SIGNATURE));
        assertFalse(traceReceived);
        traceReceived = true;
      }
    }
  }

  private void receiveProcessTrace(DecodedTrace decodedTrace) {
    for (DecodedSpan span : decodedTrace.getSpans()) {
      if (span.getName().equals("spring.handler")
          && span.getResource().equals("InterfacedController.process")
          && span.getMeta().containsKey(DDTags.DD_CODE_ORIGIN_FRAME_TYPE)) {
        assertEquals("entry", span.getMeta().get(DDTags.DD_CODE_ORIGIN_TYPE));
        assertEquals(
            "datadog.smoketest.debugger.controller.InterfacedController",
            span.getMeta().get(DDTags.DD_CODE_ORIGIN_FRAME_TYPE));
        assertEquals(
            "InterfacedController.java", span.getMeta().get(DDTags.DD_CODE_ORIGIN_FRAME_FILE));
        assertEquals("11", span.getMeta().get(DDTags.DD_CODE_ORIGIN_FRAME_LINE));
        assertEquals("process", span.getMeta().get(DDTags.DD_CODE_ORIGIN_FRAME_METHOD));
        assertEquals("()", span.getMeta().get(DDTags.DD_CODE_ORIGIN_FRAME_SIGNATURE));
        assertFalse(traceReceived);
        traceReceived = true;
      }
    }
  }
}
