package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import datadog.trace.bootstrap.debugger.ProbeId;
import datadog.trace.test.agent.decoder.DecodedSpan;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.DisabledIf;

public class ProbeFileIntegrationTest extends ServerAppDebuggerIntegrationTest {
  private static final ProbeId PROBE_ID2 = new ProbeId("ad4cba6f-d476-4554-b5ed-80dd941a40d8", 0);
  private static final ProbeId PROBE_ID3 = new ProbeId("70b55d06-f9fa-403b-a329-4f2f960aed01", 0);
  private static final ProbeId PROBE_ID4 = new ProbeId("123356537", 0);

  Path probeFilePath;

  @BeforeEach
  @Override
  public void setup(TestInfo testInfo) throws Exception {
    super.setup(testInfo);
    probeFilePath =
        Paths.get(ProbeFileIntegrationTest.class.getResource("/test_probe_file.json").toURI());
    appUrl = startAppAndAndGetUrl();
  }

  @Override
  protected ProcessBuilder createProcessBuilder(Path logFilePath, String... params) {
    List<String> commandParams = getDebuggerCommandParams();
    commandParams.add("-Ddd.trace.enabled=true"); // explicitly enable tracer
    commandParams.add("-Ddd.dynamic.instrumentation.probe.file=" + probeFilePath.toString());
    // increase eval timeout for decoration evaluations
    commandParams.add("-Ddd.dynamic.instrumentation.evaluation.timeout.ms=100");
    return ProcessBuilderHelper.createProcessBuilder(
        commandParams, logFilePath, getAppClass(), params);
  }

  @Test
  @DisplayName("testProbeFile")
  @DisabledIf(
      value = "datadog.environment.JavaVirtualMachine#isJ9",
      disabledReason = "Flaky on J9 JVMs")
  void testProbeFile() throws Exception {
    waitForInstrumentation(appUrl);
    execute(appUrl, TRACED_METHOD_NAME);
    AtomicBoolean snapshotReceived = new AtomicBoolean();
    AtomicBoolean traceReceived = new AtomicBoolean();
    registerSnapshotListener(
        snapshot -> {
          assertEquals(PROBE_ID.getId(), snapshot.getProbe().getId());
          assertEquals(5, snapshot.getCaptures().getReturn().getArguments().size());
          snapshotReceived.set(true);
        });
    registerTraceListener(
        decodedTrace -> {
          for (DecodedSpan span : decodedTrace.getSpans()) {
            if (isDynamicSpan(span)) {
              assertEquals("foobar", span.getMeta().get("client"));
              assertEquals(PROBE_ID2.getId(), span.getMeta().get("_dd.di.client.probe_id"));
              traceReceived.set(true);
            }
          }
        });
    processRequests(
        () -> snapshotReceived.get() && traceReceived.get(),
        () ->
            String.format(
                "Timeout! traceReceived=%s snapshotReceived=%s", traceReceived, snapshotReceived));
    assertFalse(logHasErrors(logFilePath, it -> it.contains(" Error ")));
  }

  protected boolean isDynamicSpan(DecodedSpan span) {
    return span.getName().equals("dd.dynamic.span")
        && span.getResource().equals("ServerDebuggerTestApplication.tracedMethod");
  }
}
