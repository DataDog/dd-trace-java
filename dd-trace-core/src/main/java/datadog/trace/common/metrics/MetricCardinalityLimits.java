package datadog.trace.common.metrics;

/**
 * Per-field limits for distinct metric-key values seen during one reporting cycle. When a field
 * exceeds its limit, additional values are replaced with {@code tracer_blocked_value} so they share
 * one aggregate row instead of creating more rows in the table.
 *
 * <p>Values are sized to the typical-service workload with headroom; "typical" estimates are noted
 * inline. Raise if a workload routinely hits the sentinel; lower carries proportional memory
 * savings but risks suppressing legitimate distinctions.
 */
final class MetricCardinalityLimits {
  private MetricCardinalityLimits() {}

  /**
   * Distinct {@code resource.name} values per cycle. Highest-cardinality field by far: DB-query
   * obfuscations, HTTP route templates, custom resources. Typical service: 30-200 unique; 1024
   * leaves headroom for high-cardinality SQL/HTTP workloads without risking premature collapse.
   */
  static final int RESOURCE = 1024;

  /**
   * Distinct {@code service.name} values per cycle. Local service plus downstream peer-service
   * names. Microservice meshes typically reference 10-50 distinct services.
   */
  static final int SERVICE = 32;

  /**
   * Distinct {@code operation.name} values per cycle. Names like {@code http.request}, {@code
   * db.query}, etc. Typical service: 10-30 across integrations.
   */
  static final int OPERATION = 64;

  /**
   * Distinct {@code _dd.base_service} override values per cycle. Used rarely; usually empty or one
   * of a handful per service.
   */
  static final int SERVICE_SOURCE = 16;

  /**
   * Distinct {@code span.type} values per cycle. {@code DDSpanTypes} catalog is ~30; a single
   * service usually spans 5-10 integration types.
   */
  static final int TYPE = 16;

  /**
   * Distinct {@code span.kind} values per cycle. OTel defines exactly 5 (server/client/producer/
   * consumer/internal); 8 still leaves 60% headroom in case a producer invents new kinds.
   */
  static final int SPAN_KIND = 8;

  /**
   * Distinct HTTP method values per cycle. Standard verbs are 7-9; WebDAV/custom adds a few more.
   */
  static final int HTTP_METHOD = 16;

  /**
   * Distinct {@code http.endpoint} values per cycle. Path templates -- same shape as {@code
   * RESOURCE} for HTTP-heavy services. Only used when {@code includeEndpointInMetrics} is enabled.
   */
  static final int HTTP_ENDPOINT = 64;

  /**
   * Distinct gRPC status code values per cycle. gRPC spec defines exactly 17 codes (0-16); 24
   * leaves headroom for unknown-code edge cases without wasting space.
   */
  static final int GRPC_STATUS_CODE = 24;

  /**
   * Distinct values per peer-tag name (e.g. distinct {@code peer.hostname} values). Each configured
   * peer tag gets its own handler at this limit.
   */
  static final int PEER_TAG_VALUE = 512;

  /**
   * Distinct values per additional-tag key (e.g. distinct values of a span-derived primary tag).
   * Each configured additional tag gets its own {@link TagCardinalityHandler} at this limit.
   */
  static final int ADDITIONAL_TAG_VALUE = 512;
}
