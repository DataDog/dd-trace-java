package datadog.trace.common.metrics;

import static datadog.trace.api.config.GeneralConfig.TRACE_STATS_COMPUTATION_ENABLED;
import static datadog.trace.api.config.OtlpConfig.OTEL_TRACES_SPAN_METRICS_ENABLED;
import static datadog.trace.api.config.OtlpConfig.OTLP_METRICS_PROTOCOL;
import static datadog.trace.api.config.OtlpConfig.TRACE_OTEL_EXPORTER;
import static datadog.trace.api.config.TracerConfig.WRITER_TYPE;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.Properties;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.tabletest.junit.TableTest;

/**
 * Tests the native-vs-OTLP XOR writer selection in {@link MetricsAggregatorFactory}. The selected
 * writer is not exposed directly, but {@link ClientStatsAggregator} publishes the selection outcome
 * via {@code isOtlpStatsExportEnabled()} plus the reporting cadence getters.
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
  void whenNativeTracerMetricsEnabledSerializingWriterSelected() {
    // tracer metrics default to enabled; OTLP span metrics default off (no OTLP trace export).
    Config config = Config.get(props());

    MetricsAggregator aggregator =
        MetricsAggregatorFactory.createMetricsAggregator(
            config, sharedCommunicationObjects(), HealthMetrics.NO_OP);

    ClientStatsAggregator conflating = assertInstanceOf(ClientStatsAggregator.class, aggregator);
    assertFalse(conflating.isOtlpStatsExportEnabled());
    // native path uses a hardcoded 10s cadence, not trace.stats.interval.
    assertEquals(10, conflating.reportingInterval());
    assertEquals(SECONDS, conflating.reportingIntervalTimeUnit());
  }

  @Test
  void whenOtlpTracesEnabledNoOpAggregatorCreatedByDefault() {
    Config config = Config.get(props(TRACE_OTEL_EXPORTER, "otlp"));

    MetricsAggregator aggregator =
        MetricsAggregatorFactory.createMetricsAggregator(
            config, sharedCommunicationObjects(), HealthMetrics.NO_OP);

    assertInstanceOf(NoOpMetricsAggregator.class, aggregator);
  }

  @Test
  void whenOtlpTraceMetricsEnabledOtlpStatsMetricWriterSelected() {
    Config config =
        Config.get(
            props(OTEL_TRACES_SPAN_METRICS_ENABLED, "true", OTLP_METRICS_PROTOCOL, "http/json"));

    MetricsAggregator aggregator =
        MetricsAggregatorFactory.createMetricsAggregator(
            config, sharedCommunicationObjects(), HealthMetrics.NO_OP);

    ClientStatsAggregator conflating = assertInstanceOf(ClientStatsAggregator.class, aggregator);
    assertTrue(conflating.isOtlpStatsExportEnabled());
    // OTLP path sources the cadence from trace.stats.interval (ms), default 10s.
    assertEquals(config.getTraceStatsInterval(), conflating.reportingInterval());
    assertEquals(MILLISECONDS, conflating.reportingIntervalTimeUnit());
  }

  @TableTest({
    "writerType                             | nativeMetrics | expectedWarning",
    "OtlpWriter                             | true          | false          ",
    "DDAgentWriter                          | true          | true           ",
    "'MultiWriter:OtlpWriter,DDAgentWriter' | true          | true           ",
    "'MultiWriter:OtlpWriter,LoggingWriter' | true          | false          ",
    "DDAgentWriter                          | false         | false          "
  })
  void warnsAboutConflictingMetricsOnlyWithNativeWriter(
      String writerType, boolean nativeMetrics, boolean expectedWarning) {
    Config config =
        Config.get(
            props(
                WRITER_TYPE,
                writerType,
                TRACE_STATS_COMPUTATION_ENABLED,
                Boolean.toString(nativeMetrics),
                OTEL_TRACES_SPAN_METRICS_ENABLED,
                "true"));
    Logger logger = (Logger) LoggerFactory.getLogger(MetricsAggregatorFactory.class);
    Level previousLevel = logger.getLevel();
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.WARN);
    try {
      MetricsAggregatorFactory.createMetricsAggregator(
          config, sharedCommunicationObjects(), HealthMetrics.NO_OP);
      assertEquals(
          expectedWarning,
          appender.list.stream()
              .anyMatch(event ->
                  event.getLevel() == Level.WARN
                      && event.getFormattedMessage().startsWith("Both OTLP trace span metrics")));
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(previousLevel);
      appender.stop();
    }
  }
}
