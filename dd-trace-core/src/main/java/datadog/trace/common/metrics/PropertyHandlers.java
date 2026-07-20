package datadog.trace.common.metrics;

import datadog.trace.api.Config;
import datadog.trace.core.monitor.HealthMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bundles the nine per-field property cardinality handlers; owned by {@link ClientStatsAggregator}.
 */
final class PropertyHandlers {

  private static final Logger log = LoggerFactory.getLogger(PropertyHandlers.class);

  final PropertyCardinalityHandler resource;
  final PropertyCardinalityHandler service;
  final PropertyCardinalityHandler operation;
  final PropertyCardinalityHandler serviceSource;
  final PropertyCardinalityHandler type;
  final PropertyCardinalityHandler spanKind;
  final PropertyCardinalityHandler httpMethod;
  final PropertyCardinalityHandler httpEndpoint;
  final PropertyCardinalityHandler grpcStatusCode;

  private final PropertyCardinalityHandler[] handlers;

  PropertyHandlers() {
    Config config = Config.get();
    // Configurable limits — tunable via env var.
    this.resource =
        new PropertyCardinalityHandler(
            "resource",
            config.getTraceStatsCardinalityLimit("resource", MetricCardinalityLimits.RESOURCE));
    this.httpEndpoint =
        new PropertyCardinalityHandler(
            "http_endpoint",
            config.getTraceStatsCardinalityLimit(
                "http_endpoint", MetricCardinalityLimits.HTTP_ENDPOINT));
    // Fixed limits — hardcoded, not user-configurable.
    this.service = new PropertyCardinalityHandler("service", MetricCardinalityLimits.SERVICE);
    this.operation = new PropertyCardinalityHandler("operation", MetricCardinalityLimits.OPERATION);
    this.serviceSource =
        new PropertyCardinalityHandler("service_source", MetricCardinalityLimits.SERVICE_SOURCE);
    this.type = new PropertyCardinalityHandler("type", MetricCardinalityLimits.TYPE);
    this.spanKind = new PropertyCardinalityHandler("span_kind", MetricCardinalityLimits.SPAN_KIND);
    this.httpMethod =
        new PropertyCardinalityHandler("http_method", MetricCardinalityLimits.HTTP_METHOD);
    this.grpcStatusCode =
        new PropertyCardinalityHandler(
            "grpc_status_code", MetricCardinalityLimits.GRPC_STATUS_CODE);
    this.handlers =
        new PropertyCardinalityHandler[] {
          resource,
          service,
          operation,
          serviceSource,
          type,
          spanKind,
          httpMethod,
          httpEndpoint,
          grpcStatusCode
        };
  }

  void reset(HealthMetrics healthMetrics) {
    for (PropertyCardinalityHandler h : handlers) {
      long numBlocked = h.reset();
      if (numBlocked > 0) {
        log.warn(
            "Cardinality limit reached for stats field '{}'; further values will be reported as tracer_blocked_value",
            h.name);
        healthMetrics.onTagCardinalityBlocked(h.statsDTag(), numBlocked);
      }
    }
  }
}
