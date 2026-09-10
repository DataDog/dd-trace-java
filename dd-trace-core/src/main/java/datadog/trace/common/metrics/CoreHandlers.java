package datadog.trace.common.metrics;

import datadog.trace.api.Config;
import datadog.trace.api.metrics.StatsMetrics;
import datadog.trace.core.monitor.HealthMetrics;

/**
 * The core set of cardinality handlers -- the always-present per-field handlers, as opposed to the
 * remote-config-driven peer-tag set ({@link PeerTagSchema}) and the local-config additional-tag set
 * ({@link AdditionalTagsSchema}). Whether a member is a property or a tag handler is an
 * implementation detail of the core set. Owned by {@link ClientStatsAggregator}.
 */
final class CoreHandlers {

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

  CoreHandlers() {
    Config config = Config.get();
    // Configurable limits — tunable via env var.
    this.resource =
        new PropertyCardinalityHandler(
            "resource",
            config.getTraceStatsCardinalityLimit("resource", MetricCardinalityLimits.RESOURCE),
            MetricCardinalityLimits.USE_BLOCKED_SENTINEL);
    this.httpEndpoint =
        new PropertyCardinalityHandler(
            "http_endpoint",
            config.getTraceStatsCardinalityLimit(
                "http_endpoint", MetricCardinalityLimits.HTTP_ENDPOINT),
            MetricCardinalityLimits.USE_BLOCKED_SENTINEL);
    // Fixed limits — hardcoded, not user-configurable.
    this.service =
        new PropertyCardinalityHandler(
            "service",
            MetricCardinalityLimits.SERVICE,
            MetricCardinalityLimits.USE_BLOCKED_SENTINEL);
    // operation is carried on the stats wire as the protobuf field "name", so its telemetry tag is
    // collapsed:name (RFC section 5) while the human-facing reporter keeps the clearer "operation".
    this.operation =
        new PropertyCardinalityHandler(
            "operation",
            "name",
            MetricCardinalityLimits.OPERATION,
            MetricCardinalityLimits.USE_BLOCKED_SENTINEL);
    this.serviceSource =
        new PropertyCardinalityHandler(
            "service_source",
            MetricCardinalityLimits.SERVICE_SOURCE,
            MetricCardinalityLimits.USE_BLOCKED_SENTINEL);
    this.type =
        new PropertyCardinalityHandler(
            "type", MetricCardinalityLimits.TYPE, MetricCardinalityLimits.USE_BLOCKED_SENTINEL);
    this.spanKind =
        new PropertyCardinalityHandler(
            "span_kind",
            MetricCardinalityLimits.SPAN_KIND,
            MetricCardinalityLimits.USE_BLOCKED_SENTINEL);
    this.httpMethod =
        new PropertyCardinalityHandler(
            "http_method",
            MetricCardinalityLimits.HTTP_METHOD,
            MetricCardinalityLimits.USE_BLOCKED_SENTINEL);
    this.grpcStatusCode =
        new PropertyCardinalityHandler(
            "grpc_status_code",
            MetricCardinalityLimits.GRPC_STATUS_CODE,
            MetricCardinalityLimits.USE_BLOCKED_SENTINEL);
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

  void reset(HealthMetrics healthMetrics, CardinalityLimitReporter reporter) {
    for (PropertyCardinalityHandler h : handlers) {
      long numBlocked = h.reset();
      if (numBlocked > 0) {
        String[] statsDTag = h.statsDTag();
        healthMetrics.onTagCardinalityBlocked(statsDTag, numBlocked);
        StatsMetrics.getInstance().onCollapsedSpans(statsDTag[0], numBlocked);
        reporter.record(h.name, numBlocked);
      }
    }
  }
}
