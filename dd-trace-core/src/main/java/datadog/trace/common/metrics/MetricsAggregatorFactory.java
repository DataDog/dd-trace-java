package datadog.trace.common.metrics;

import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.api.Config;
import datadog.trace.core.monitor.HealthMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MetricsAggregatorFactory {
  private static final Logger log = LoggerFactory.getLogger(MetricsAggregatorFactory.class);

  public static MetricsAggregator createMetricsAggregator(
      Config config,
      SharedCommunicationObjects sharedCommunicationObjects,
      HealthMetrics healthMetrics) {
    // OTLP span-metrics export and native msgpack stats are mutually exclusive (XOR): both hang off
    // the same ClientStatsAggregator span selection + DDSketch aggregation, differing only in
    // the injected MetricWriter.
    if (config.isTracesSpanMetricsEnabled()) {
      if (config.isTracerMetricsEnabled()) {
        log.warn(
            "Both OTLP trace span metrics and native tracer metrics are enabled; "
                + "using OTLP export and ignoring native tracer metrics (the two are mutually "
                + "exclusive).");
      }
      if (!config.isMetricsOtlpExporterEnabled()) {
        // Reachable only via an explicit OTEL_TRACES_SPAN_METRICS_ENABLED=true: the implicit
        // default already requires the OTLP metrics exporter. The span-metrics writer is not
        // gated by metrics.otel.exporter -- it builds its sender from the otlp.metrics.* transport
        // settings -- so metrics still leave over OTLP even though the general OTel metrics
        // exporter is not OTLP. Warn so this is not a silent surprise.
        log.warn(
            "OTLP trace span metrics are enabled but the OTLP metrics exporter is not "
                + "(metrics.otel.exporter is not 'otlp'); span metrics will still be exported over "
                + "OTLP using the otlp.metrics.* transport settings. Set metrics.otel.exporter=otlp "
                + "to make this explicit, or disable traces.span.metrics.enabled to suppress them.");
      }
      log.debug("OTLP trace span metrics enabled");
      return new ClientStatsAggregator(
          config, sharedCommunicationObjects, healthMetrics, new OtlpStatsMetricWriter(config));
    }
    if (config.isTracerMetricsEnabled()) {
      log.debug("tracer metrics enabled");
      return new ClientStatsAggregator(config, sharedCommunicationObjects, healthMetrics);
    }
    log.debug("tracer metrics disabled");
    return NoOpMetricsAggregator.INSTANCE;
  }
}
