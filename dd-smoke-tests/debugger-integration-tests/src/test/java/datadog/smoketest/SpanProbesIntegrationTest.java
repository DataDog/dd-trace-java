package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadog.debugger.agent.DebuggerTracer;
import com.datadog.debugger.agent.ProbeStatus;
import com.datadog.debugger.probe.SpanProbe;
import datadog.trace.test.agent.decoder.DecodedSpan;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class SpanProbesIntegrationTest extends SimpleAppDebuggerIntegrationTest {

  @Override
  protected ProcessBuilder createProcessBuilder(Path logFilePath, String... params) {
    List<String> commandParams = getDebuggerCommandParams();
    commandParams.add("-Ddd.trace.enabled=true"); // explicitly enable tracer
    return ProcessBuilderHelper.createProcessBuilder(
        commandParams, logFilePath, getAppClass(), params);
  }

  @Test
  @DisplayName("testMethodSpan")
  void testMethodSpan() throws Exception {
    final String METHOD_NAME = "fullMethod";
    final String EXPECTED_UPLOADS = "3"; // 2 + 1 for letting the trace being sent (async)
    SpanProbe spanProbe =
        SpanProbe.builder().probeId(PROBE_ID).where(MAIN_CLASS_NAME, METHOD_NAME).build();
    setCurrentConfiguration(createSpanConfig(spanProbe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();
    DecodedSpan decodedSpan = retrieveSpanRequest(DebuggerTracer.OPERATION_NAME);
    assertEquals("Main.fullMethod", decodedSpan.getResource());
  }

  @Test
  @DisplayName("testLineRangeSpan")
  void testLineRangeSpan() throws Exception {
    final String METHOD_NAME = "fullMethod";
    final String EXPECTED_UPLOADS = "3"; // 2 + 1 for letting the trace being sent (async)
    SpanProbe spanProbe =
        SpanProbe.builder().probeId(PROBE_ID).where(MAIN_CLASS_NAME, 68, 77).build();
    setCurrentConfiguration(createSpanConfig(spanProbe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();
    DecodedSpan decodedSpan = retrieveSpanRequest(DebuggerTracer.OPERATION_NAME);
    assertEquals("Main.fullMethod:L68-77", decodedSpan.getResource());
  }

  @Test
  @DisplayName("testSingleLineSpan")
  void testSingleLineSpan() throws Exception {
    final String METHOD_NAME = "fullMethod";
    final String EXPECTED_UPLOADS = "2"; // 2 probe statuses: RECEIVED + ERROR
    SpanProbe spanProbe = SpanProbe.builder().probeId(PROBE_ID).where(MAIN_CLASS_NAME, 68).build();
    setCurrentConfiguration(createSpanConfig(spanProbe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();
    ProbeStatus status = retrieveProbeStatusRequest();
    assertEquals(ProbeStatus.Status.RECEIVED, status.getDiagnostics().getStatus());
    status = retrieveProbeStatusRequest();
    assertEquals(ProbeStatus.Status.ERROR, status.getDiagnostics().getStatus());
    assertEquals(
        "Single line span is not supported, you need to provide a range.",
        status.getDiagnostics().getException().getMessage());
  }
}
