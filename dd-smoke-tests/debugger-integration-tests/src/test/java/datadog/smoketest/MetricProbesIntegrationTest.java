package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.datadog.debugger.agent.ProbeStatus;
import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.probe.MetricProbe;
import datadog.trace.test.util.Flaky;
import datadog.trace.test.util.NonRetryable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@Flaky
@NonRetryable
public class MetricProbesIntegrationTest extends SimpleAppDebuggerIntegrationTest {

  @AfterEach
  void teardown() throws Exception {
    processRemainingRequests();
  }

  @Test
  @DisplayName("testMethodMetricInc")
  void testMethodMetricInc() throws Exception {
    doMethodMetric(
        "incCallCount",
        MetricProbe.MetricKind.COUNT,
        null,
        "dynamic.instrumentation.metric.probe.%s:1|c|#debugger.probeid:%s");
  }

  @Test
  @DisplayName("testMethodMetricCount")
  void testMethodMetricCount() throws Exception {
    doMethodMetric(
        "fullMethod_count",
        MetricProbe.MetricKind.COUNT,
        new ValueScript(DSL.ref("argInt"), "argInt"),
        "dynamic.instrumentation.metric.probe.%s:42|c|#debugger.probeid:%s");
  }

  @Test
  @DisplayName("testMethodMetricCountInvalidSymbol")
  void testMethodMetricCountInvalidSymbol() throws Exception {
    doMethodInvalidMetric(
        "fullMethod_count",
        MetricProbe.MetricKind.COUNT,
        new ValueScript(DSL.ref("noarg"), "noarg"),
        "Cannot resolve symbol noarg");
  }

  @Test
  @DisplayName("testMethodMetricCountInvalidType")
  void testMethodMetricCountInvalidType() throws Exception {
    doMethodInvalidMetric(
        "fullMethod_count",
        MetricProbe.MetricKind.COUNT,
        new ValueScript(DSL.ref("argStr"), "argStr"),
        "Incompatible type for expression: java.lang.String with expected types: [long]");
  }

  @Test
  @DisplayName("testMethodMetricGauge")
  void testMethodMetricGauge() throws Exception {
    doMethodMetric(
        "fullMethod_gauge",
        MetricProbe.MetricKind.GAUGE,
        new ValueScript(DSL.ref("argInt"), "argInt"),
        "dynamic.instrumentation.metric.probe.%s:42|g|#debugger.probeid:%s");
  }

  @Test
  @DisplayName("testMethodMetricHistogram")
  void testMethodMetricHistogram() throws Exception {
    doMethodMetric(
        "fullMethod_histogram",
        MetricProbe.MetricKind.HISTOGRAM,
        new ValueScript(DSL.ref("argInt"), "argInt"),
        "dynamic.instrumentation.metric.probe.%s:42|h|#debugger.probeid:%s");
  }

  @Test
  @DisplayName("testMethodMetricDistribution")
  void testMethodMetricDistribution() throws Exception {
    doMethodMetric(
        "fullMethod_distribution",
        MetricProbe.MetricKind.DISTRIBUTION,
        new ValueScript(DSL.ref("argInt"), "argInt"),
        "dynamic.instrumentation.metric.probe.%s:42|d|#debugger.probeid:%s");
  }

  private void doMethodMetric(
      String metricName, MetricProbe.MetricKind kind, ValueScript script, String expectedMsgFormat)
      throws IOException, InterruptedException {
    final String METHOD_NAME = "fullMethod";
    final String EXPECTED_UPLOADS =
        "-1"; // wait for TIMEOUT_S for letting the metric being sent (async)
    MetricProbe metricProbe =
        MetricProbe.builder()
            .probeId(PROBE_ID)
            .where(MAIN_CLASS_NAME, METHOD_NAME)
            .kind(kind)
            .metricName(metricName)
            .valueScript(script)
            .build();
    setCurrentConfiguration(createMetricConfig(metricProbe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();
    String msgExpected = String.format(expectedMsgFormat, metricName, PROBE_ID.getId());
    assertNotNull(retrieveStatsdMessage(msgExpected));
    AtomicBoolean statusResult = registerCheckReceivedInstalledEmitting();
    processRequests(
        statusResult::get, () -> String.format("timeout statusResult=%s", statusResult.get()));
  }

  private void doMethodInvalidMetric(
      String metricName, MetricProbe.MetricKind kind, ValueScript script, String expectedMsg)
      throws Exception {
    final String METHOD_NAME = "fullMethod";
    final String EXPECTED_UPLOADS =
        "-1"; // wait for TIMEOUT_S for letting the Probe Status to be sent (async)
    MetricProbe metricProbe =
        MetricProbe.builder()
            .probeId(PROBE_ID)
            .where(MAIN_CLASS_NAME, METHOD_NAME)
            .kind(kind)
            .metricName(metricName)
            .valueScript(script)
            .build();
    setCurrentConfiguration(createMetricConfig(metricProbe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();
    AtomicBoolean received = new AtomicBoolean();
    AtomicBoolean error = new AtomicBoolean();
    registerProbeStatusListener(
        probeStatus -> {
          if (probeStatus.getDiagnostics().getStatus() == ProbeStatus.Status.RECEIVED) {
            received.set(true);
          }
          if (probeStatus.getDiagnostics().getStatus() == ProbeStatus.Status.ERROR) {
            assertEquals(expectedMsg, probeStatus.getDiagnostics().getException().getMessage());
            error.set(true);
          }
        });
    processRequests(
        () -> received.get() && error.get(),
        () -> String.format("timeout received=%s error=%s", received.get(), error.get()));
  }

  @Test
  @DisplayName("testLineMetricInc")
  void testLineMetricInc() throws Exception {
    doLineMetric(
        "fullMethod_incCallCount",
        MetricProbe.MetricKind.COUNT,
        null,
        "dynamic.instrumentation.metric.probe.%s:1|c|#debugger.probeid:%s");
  }

  @Test
  @DisplayName("testLineMetricCount")
  void testLineMetricCount() throws Exception {
    doLineMetric(
        "fullMethod_count",
        MetricProbe.MetricKind.COUNT,
        new ValueScript(DSL.ref("argInt"), "argInt"),
        "dynamic.instrumentation.metric.probe.%s:42|c|#debugger.probeid:%s");
  }

  @Test
  @DisplayName("testLineMetricGauge")
  void testLineMetricGauge() throws Exception {
    doLineMetric(
        "fullMethod_gauge",
        MetricProbe.MetricKind.GAUGE,
        new ValueScript(DSL.ref("argInt"), "argInt"),
        "dynamic.instrumentation.metric.probe.%s:42|g|#debugger.probeid:%s");
  }

  @Test
  @DisplayName("testLineMetricHistogram")
  void testLineMetricHistogram() throws Exception {
    doLineMetric(
        "fullMethod_histogram",
        MetricProbe.MetricKind.HISTOGRAM,
        new ValueScript(DSL.ref("argInt"), "argInt"),
        "dynamic.instrumentation.metric.probe.%s:42|h|#debugger.probeid:%s");
  }

  @Test
  @DisplayName("testLineMetricDistribution")
  void testLineMetricDistribution() throws Exception {
    doLineMetric(
        "fullMethod_distribution",
        MetricProbe.MetricKind.DISTRIBUTION,
        new ValueScript(DSL.ref("argInt"), "argInt"),
        "dynamic.instrumentation.metric.probe.%s:42|d|#debugger.probeid:%s");
  }

  private void doLineMetric(
      String metricName, MetricProbe.MetricKind kind, ValueScript script, String expectedMsgFormat)
      throws IOException, InterruptedException {
    final String METHOD_NAME = "fullMethod";
    final String EXPECTED_UPLOADS =
        "-1"; // wait for TIMEOUT_S for letting the metric being sent (async)
    MetricProbe metricProbe =
        MetricProbe.builder()
            .probeId(PROBE_ID)
            // on line: System.out.println("fullMethod");
            .where("DebuggerTestApplication.java", 88)
            .kind(kind)
            .metricName(metricName)
            .valueScript(script)
            .build();
    setCurrentConfiguration(createMetricConfig(metricProbe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();
    String msgExpected = String.format(expectedMsgFormat, metricName, PROBE_ID.getId());
    assertNotNull(retrieveStatsdMessage(msgExpected));
    AtomicBoolean statusResult = registerCheckReceivedInstalledEmitting();
    processRequests(
        statusResult::get, () -> String.format("timeout statusResult=%s", statusResult.get()));
  }
}
