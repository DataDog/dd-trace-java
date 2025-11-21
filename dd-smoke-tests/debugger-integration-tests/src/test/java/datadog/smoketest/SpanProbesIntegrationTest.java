package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadog.debugger.agent.ProbeStatus;
import com.datadog.debugger.probe.SpanProbe;
import datadog.trace.test.agent.decoder.DecodedSpan;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

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
    final String EXPECTED_UPLOADS = "4"; // 3 statuses + 1 for letting the trace being sent (async)
    SpanProbe spanProbe =
        SpanProbe.builder().probeId(PROBE_ID).where(MAIN_CLASS_NAME, METHOD_NAME).build();
    setCurrentConfiguration(createSpanConfig(spanProbe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();

    AtomicBoolean statusResult = registerCheckReceivedInstalledEmitting();
    AtomicBoolean traceReceived = new AtomicBoolean();
    registerTraceListener(
        decodedTrace -> {
          DecodedSpan decodedSpan = decodedTrace.getSpans().get(0);
          assertEquals("Main.fullMethod", decodedSpan.getResource());
          traceReceived.set(true);
        });
    processRequests(
        () -> statusResult.get() && traceReceived.get(),
        () ->
            String.format(
                "timeout statusResult=%s traceReceived=%s",
                statusResult.get(), traceReceived.get()));
  }

  @Test
  @DisplayName("testLineRangeSpan")
  void testLineRangeSpan() throws Exception {
    final String METHOD_NAME = "fullMethod";
    final String EXPECTED_UPLOADS = "4"; // 3 statuses + 1 for letting the trace being sent (async)
    SpanProbe spanProbe =
        SpanProbe.builder()
            .probeId(PROBE_ID)
            // from line: System.out.println("fullMethod");
            // to line: + String.join(",", argVar);
            .where(MAIN_CLASS_NAME, 88, 97)
            .build();
    setCurrentConfiguration(createSpanConfig(spanProbe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();
    AtomicBoolean statusResult = registerCheckReceivedInstalledEmitting();
    AtomicBoolean traceReceived = new AtomicBoolean();
    registerTraceListener(
        decodedTrace -> {
          DecodedSpan decodedSpan = decodedTrace.getSpans().get(0);
          assertEquals("Main.fullMethod:L88-97", decodedSpan.getResource());
          traceReceived.set(true);
        });
    processRequests(
        () -> statusResult.get() && traceReceived.get(),
        () ->
            String.format(
                "timeout statusResult=%s traceReceived=%s",
                statusResult.get(), traceReceived.get()));
  }

  @Test
  @DisplayName("testSingleLineSpan")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  void testSingleLineSpan() throws Exception {
    final String METHOD_NAME = "fullMethod";
    final String EXPECTED_UPLOADS = "2"; // 2 probe statuses: RECEIVED + ERROR
    SpanProbe spanProbe =
        SpanProbe.builder()
            .probeId(PROBE_ID)
            // on line: System.out.println("fullMethod");
            .where(MAIN_CLASS_NAME, 88)
            .build();
    setCurrentConfiguration(createSpanConfig(spanProbe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();
    AtomicBoolean received = new AtomicBoolean(false);
    AtomicBoolean error = new AtomicBoolean(false);
    registerProbeStatusListener(
        probeStatus -> {
          if (probeStatus.getDiagnostics().getStatus() == ProbeStatus.Status.RECEIVED) {
            received.set(true);
          }
          if (probeStatus.getDiagnostics().getStatus() == ProbeStatus.Status.ERROR) {
            assertEquals(
                "Single line span is not supported, you need to provide a range.",
                probeStatus.getDiagnostics().getException().getMessage());
            error.set(true);
          }
        });
    processRequests(
        () -> received.get() && error.get(),
        () -> String.format("timeout received=%s error=%s", received.get(), error.get()));
  }
}
