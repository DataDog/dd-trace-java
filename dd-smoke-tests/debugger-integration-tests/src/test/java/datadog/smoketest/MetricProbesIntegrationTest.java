package datadog.smoketest;

import static org.junit.Assert.assertNotNull;

import com.datadog.debugger.el.DSL;
import com.datadog.debugger.el.ValueScript;
import com.datadog.debugger.probe.MetricProbe;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

public class MetricProbesIntegrationTest extends SimpleAppDebuggerIntegrationTest {

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
      throws IOException {
    final String METHOD_NAME = "fullMethod";
    final String EXPECTED_UPLOADS = "3"; // 2 + 1 for letting the metric being sent (async)
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
      throws IOException {
    final String METHOD_NAME = "fullMethod";
    final String EXPECTED_UPLOADS = "3"; // 2 + 1 for letting the metric being sent (async)
    MetricProbe metricProbe =
        MetricProbe.builder()
            .probeId(PROBE_ID)
            .where("DebuggerTestApplication.java", 69)
            .kind(kind)
            .metricName(metricName)
            .valueScript(script)
            .build();
    setCurrentConfiguration(createMetricConfig(metricProbe));
    targetProcess = createProcessBuilder(logFilePath, METHOD_NAME, EXPECTED_UPLOADS).start();
    String msgExpected = String.format(expectedMsgFormat, metricName, PROBE_ID.getId());
    assertNotNull(retrieveStatsdMessage(msgExpected));
  }
}
