package datadog.trace.common.metrics;

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

  PropertyHandlers(boolean limitsEnabled) {
    this.resource =
        new PropertyCardinalityHandler("resource", MetricCardinalityLimits.RESOURCE, limitsEnabled);
    this.service =
        new PropertyCardinalityHandler("service", MetricCardinalityLimits.SERVICE, limitsEnabled);
    this.operation =
        new PropertyCardinalityHandler(
            "operation", MetricCardinalityLimits.OPERATION, limitsEnabled);
    this.serviceSource =
        new PropertyCardinalityHandler(
            "service_source", MetricCardinalityLimits.SERVICE_SOURCE, limitsEnabled);
    this.type = new PropertyCardinalityHandler("type", MetricCardinalityLimits.TYPE, limitsEnabled);
    this.spanKind =
        new PropertyCardinalityHandler(
            "span_kind", MetricCardinalityLimits.SPAN_KIND, limitsEnabled);
    this.httpMethod =
        new PropertyCardinalityHandler(
            "http_method", MetricCardinalityLimits.HTTP_METHOD, limitsEnabled);
    this.httpEndpoint =
        new PropertyCardinalityHandler(
            "http_endpoint", MetricCardinalityLimits.HTTP_ENDPOINT, limitsEnabled);
    this.grpcStatusCode =
        new PropertyCardinalityHandler(
            "grpc_status_code", MetricCardinalityLimits.GRPC_STATUS_CODE, limitsEnabled);
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
      long blocked = h.reset();
      if (blocked > 0) {
        log.warn(
            "Cardinality limit reached for stats field '{}'; further values will be reported as blocked_by_tracer",
            h.name);
        healthMetrics.onTagCardinalityBlocked(h.statsDTag(), blocked);
      }
    }
  }
}
