package datadog.trace.common.metrics;

import static datadog.trace.api.config.GeneralConfig.TRACE_STATS_COMPUTATION_ENABLED;
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_PROTOCOL;
import static datadog.trace.api.config.OtlpConfig.TRACES_SPAN_METRICS_ENABLED;
import static datadog.trace.api.config.OtlpConfig.TRACE_OTEL_EXPORTER;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.core.monitor.HealthMetrics;
import java.lang.reflect.Field;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;

/**
 * Tests the native-vs-OTLP XOR writer selection in {@link MetricsAggregatorFactory}. The selected
 * {@link MetricWriter} is not exposed publicly, so it is read via reflection through {@code
 * ClientStatsAggregator.aggregator -> Aggregator.writer}.
 */
class MetricsAggregatorFactoryTest {

  private static SharedCommunicationObjects sharedCommunicationObjects() {
    SharedCommunicationObjects sco = mock(SharedCommunicationObjects.class);
    sco.agentUrl = HttpUrl.parse("http://localhost:8126");
    when(sco.featuresDiscovery(any())).thenReturn(mock(DDAgentFeaturesDiscovery.class));
    return sco;
  }

  private static Properties props(String... keyValues) {
    Properties props = new Properties();
    for (int i = 0; i < keyValues.length; i += 2) {
      props.setProperty(keyValues[i], keyValues[i + 1]);
    }
    return props;
  }

  @Test
  void whenAllMetricsDisabledNoOpAggregatorCreated() {
    Config config = Config.get(props(TRACE_STATS_COMPUTATION_ENABLED, "false"));

    MetricsAggregator aggregator =
        MetricsAggregatorFactory.createMetricsAggregator(
            config, sharedCommunicationObjects(), HealthMetrics.NO_OP);

    assertInstanceOf(NoOpMetricsAggregator.class, aggregator);
  }

  @Test
  void whenNativeTracerMetricsEnabledSerializingWriterSelected() throws Exception {
    // tracer metrics default to enabled; OTLP span metrics default off (no OTLP trace export).
    Config config = Config.get(props());

    MetricsAggregator aggregator =
        MetricsAggregatorFactory.createMetricsAggregator(
            config, sharedCommunicationObjects(), HealthMetrics.NO_OP);

    assertInstanceOf(ClientStatsAggregator.class, aggregator);
    assertInstanceOf(SerializingMetricWriter.class, writerOf(aggregator));
  }

  @Test
  void whenOtlpSpanMetricsEnabledOtlpWriterSelectedWithConfiguredInterval() throws Exception {
    // OTEL_TRACES_EXPORTER=otlp satisfies FR16 (traces not routed through an Agent). http/json has
    // no protobuf-free encoder, so the writer's sender is null -- keeps construction lightweight
    // (no
    // real OTLP sender / network) while still exercising writer selection.
    Config config =
        Config.get(
            props(
                TRACES_SPAN_METRICS_ENABLED, "true",
                TRACE_OTEL_EXPORTER, "otlp",
                OTLP_METRICS_PROTOCOL, "http/json"));

    MetricsAggregator aggregator =
        MetricsAggregatorFactory.createMetricsAggregator(
            config, sharedCommunicationObjects(), HealthMetrics.NO_OP);

    assertInstanceOf(ClientStatsAggregator.class, aggregator);
    assertInstanceOf(OtlpStatsMetricWriter.class, writerOf(aggregator));
    // reporting interval comes from getTraceStatsInterval() (ms), default 10s.
    assertEquals(config.getTraceStatsInterval(), reportingIntervalOf(aggregator));
    assertEquals(MILLISECONDS, reportingIntervalUnitOf(aggregator));
  }

  @Test
  void explicitSpanMetricsEnabledSelectsOtlpWriterEvenWithoutOtlpTraceExport() throws Exception {
    // An explicit OTEL_TRACES_SPAN_METRICS_ENABLED=true is honored verbatim regardless of trace
    // exporter (matching the dd-trace-py / dd-trace-go reference impls). Double-counting if these
    // OTLP spans reach an Agent is handled by the FR15 _dd.stats_computed marker, not by disabling
    // here. http/json keeps the writer's sender null (no network at construction).
    Config config =
        Config.get(props(TRACES_SPAN_METRICS_ENABLED, "true", OTLP_METRICS_PROTOCOL, "http/json"));

    MetricsAggregator aggregator =
        MetricsAggregatorFactory.createMetricsAggregator(
            config, sharedCommunicationObjects(), HealthMetrics.NO_OP);

    assertInstanceOf(ClientStatsAggregator.class, aggregator);
    assertInstanceOf(OtlpStatsMetricWriter.class, writerOf(aggregator));
  }

  // ── reflection helpers ─────────────────────────────────────────────────────

  private static MetricWriter writerOf(MetricsAggregator aggregator) throws Exception {
    Field aggregatorField = ClientStatsAggregator.class.getDeclaredField("aggregator");
    aggregatorField.setAccessible(true);
    Object inner = aggregatorField.get(aggregator);
    Field writerField = Aggregator.class.getDeclaredField("writer");
    writerField.setAccessible(true);
    return (MetricWriter) writerField.get(inner);
  }

  private static long reportingIntervalOf(MetricsAggregator aggregator) throws Exception {
    Field f = ClientStatsAggregator.class.getDeclaredField("reportingInterval");
    f.setAccessible(true);
    return f.getLong(aggregator);
  }

  private static TimeUnit reportingIntervalUnitOf(MetricsAggregator aggregator) throws Exception {
    Field f = ClientStatsAggregator.class.getDeclaredField("reportingIntervalTimeUnit");
    f.setAccessible(true);
    return (TimeUnit) f.get(aggregator);
  }
}
