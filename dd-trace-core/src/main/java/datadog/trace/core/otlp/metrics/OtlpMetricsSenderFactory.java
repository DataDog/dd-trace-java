package datadog.trace.core.otlp.metrics;

import datadog.trace.api.Config;
import datadog.trace.core.otlp.common.OtlpGrpcSender;
import datadog.trace.core.otlp.common.OtlpHttpSender;
import datadog.trace.core.otlp.common.OtlpSender;
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
    switch (config.getOtlpMetricsProtocol()) {
      case GRPC:
        return new OtlpGrpcSender(
            config.getOtlpMetricsEndpoint(),
            "/opentelemetry.proto.collector.metrics.v1.MetricsService/Export",
            config.getOtlpMetricsHeaders(),
            config.getOtlpMetricsTimeout(),
            config.getOtlpMetricsCompression());
      case HTTP_PROTOBUF:
      case HTTP_JSON:
        return new OtlpHttpSender(
            config.getOtlpMetricsEndpoint(),
            "/v1/metrics",
            config.getOtlpMetricsHeaders(),
            config.getOtlpMetricsTimeout(),
            config.getOtlpMetricsCompression());
      default:
        return null;
    }
  }
}
