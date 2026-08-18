package datadog.trace.core.otlp.metrics;

import datadog.communication.otlp.OtlpSender;
import datadog.communication.otlp.OtlpSenderFactory;
import datadog.trace.api.Config;
import javax.annotation.Nullable;

/**
 * Selects the {@link OtlpSender} for the configured OTLP metrics protocol. Shared by every OTLP
 * metrics export path ({@code OtlpMetricsService} for OpenTelemetry-API metrics, {@code
 * OtlpStatsMetricWriter} for trace stats) so they all pick their transport identically and stay in
 * sync as protocols/endpoints evolve.
 */
final class OtlpMetricsSenderFactory {
  private OtlpMetricsSenderFactory() {}

  /**
   * Builds the sender for {@code config}'s OTLP metrics protocol, or {@code null} if the protocol
   * is unsupported.
   */
  @Nullable
  static OtlpSender create(Config config) {
    return OtlpSenderFactory.create(
        config.getOtlpMetricsProtocol(),
        config.getOtlpMetricsEndpoint(),
        "/opentelemetry.proto.collector.metrics.v1.MetricsService/Export",
        "/v1/metrics",
        config.getOtlpMetricsHeaders(),
        config.getOtlpMetricsTimeout(),
        config.getOtlpMetricsCompression());
  }
}
