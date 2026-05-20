package datadog.trace.common.metrics;

/**
 * Per-field caps on the number of distinct values canonicalized per reporting cycle. Overflow
 * values collapse to a {@code blocked_by_tracer} sentinel so they merge into one aggregate row
 * instead of fragmenting the table.
 *
 * <p>Values are sized to cover realistic workload cardinality per 10s reporting window with
 * headroom -- the prior DDCache-inherited limits (RESOURCE=32, OPERATION=64, ...) were chosen for
 * memory conservation and were tight enough that a single REST API with a couple hundred routes
 * would exhaust the budget within seconds. Memory cost with the flat handler tables is ~20 KB
 * across all 9 handlers -- negligible relative to the {@code maxAggregates}-sized entry table.
 */
final class MetricCardinalityLimits {
  private MetricCardinalityLimits() {}

  /**
   * Distinct {@code resource.name} values per cycle. Highest-cardinality field: HTTP route
   * templates, SQL query templates, custom resources. A web app with one parameterized route per
   * controller method easily hits low hundreds.
   */
  static final int RESOURCE = 512;

  /**
   * Distinct {@code service.name} values per cycle. Local service plus downstream peer-service
   * names; sized for service-mesh hubs that fan out to many downstreams.
   */
  static final int SERVICE = 128;

  /**
   * Distinct {@code operation.name} values per cycle. Names like {@code http.request}, {@code
   * db.query}, etc. One per integration kind; production services often span 30-60.
   */
  static final int OPERATION = 128;

  /**
   * Distinct {@code _dd.base_service} override values per cycle. Used rarely; usually empty or one
   * of a handful per service.
   */
  static final int SERVICE_SOURCE = 16;

  /**
   * Distinct {@code span.type} values per cycle. {@code DDSpanTypes} catalog has ~30 known values;
   * a single service typically spans 5-10 integration types.
   */
  static final int TYPE = 32;

  /**
   * Distinct {@code span.kind} values per cycle. OTel defines 5 standard kinds (server/client/
   * producer/consumer/internal); the 16 cap leaves headroom in case producers invent new kinds.
   */
  static final int SPAN_KIND = 16;

  /**
   * Distinct HTTP method values per cycle. Standard verbs are 7-9; WebDAV/custom adds a few more.
   */
  static final int HTTP_METHOD = 16;

  /**
   * Distinct {@code http.endpoint} values per cycle. Path templates -- same shape as {@code
   * RESOURCE} for HTTP-heavy services. Only used when {@code includeEndpointInMetrics} is enabled.
   */
  static final int HTTP_ENDPOINT = 256;

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
}
