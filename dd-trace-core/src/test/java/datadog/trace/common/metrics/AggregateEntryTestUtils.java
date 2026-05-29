package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.annotation.Nullable;

/**
 * Test-side helpers for {@link AggregateEntry}: a positional-args fixture factory plus a field-wise
 * equality contract for use with Spock mock argument matchers and JUnit assertions. Lives in {@code
 * src/test} so the production class stays free of test-only API; same {@code
 * datadog.trace.common.metrics} package so this helper can reach package-private fields and
 * constructors.
 *
 * <p>Production {@code AggregateEntry} intentionally has no {@code equals}/{@code hashCode}
 * override -- {@link AggregateTable} bucketing goes through {@link AggregateEntry#matches} keyed on
 * {@link AggregateEntry#keyHash}, and no production code path invokes {@link Object#equals}.
 *
 * <p>The equality helper compares the raw {@code peerTagNames}/{@code peerTagValues} arrays (not
 * the encoded {@code peerTags} list) so it stays consistent with {@link AggregateEntry#hashOf},
 * which folds in raw arrays via {@link PeerTagSchema#hashCode()} and {@link
 * Arrays#hashCode(Object[])}. Comparing the encoded list would let two entries with different raw
 * layouts (e.g. tag {@code "b"} at index 1 in schema A vs index 0 in schema B, with matching
 * values) collapse to the same encoded form -- a real bug surfaced during PR #11382 review.
 */
public final class AggregateEntryTestUtils {
  private AggregateEntryTestUtils() {}

  /**
   * Builds an {@link AggregateEntry} from the same positional shape the prior {@code new
   * MetricKey(...)} took. Accepts a pre-encoded {@code List<UTF8BytesString>} of {@code
   * "name:value"} peer tags and recovers the parallel-array {@code (names, values)} form by
   * splitting on the {@code ':'} delimiter.
   *
   * <p><b>Test-only.</b> The split is at the <em>first</em> {@code ':'}, so peer-tag values
   * containing a colon (URLs, IPv6 addresses, {@code service:env} patterns) will be silently
   * misparsed and the recovered (name, value) pair will be wrong. Keep test data colon-free in
   * peer-tag values, or wire a production-style snapshot through {@link #forSnapshot(SpanSnapshot)}
   * directly instead.
   */
  public static AggregateEntry of(
      CharSequence resource,
      CharSequence service,
      CharSequence operationName,
      @Nullable CharSequence serviceSource,
      CharSequence type,
      int httpStatusCode,
      boolean synthetic,
      boolean traceRoot,
      CharSequence spanKind,
      @Nullable List<UTF8BytesString> peerTags,
      @Nullable CharSequence httpMethod,
      @Nullable CharSequence httpEndpoint,
      @Nullable CharSequence grpcStatusCode) {
    PeerTagSchema schema = null;
    String[] values = null;
    if (peerTags != null && !peerTags.isEmpty()) {
      String[] names = new String[peerTags.size()];
      values = new String[peerTags.size()];
      int i = 0;
      for (UTF8BytesString t : peerTags) {
        String s = t.toString();
        int colon = s.indexOf(':');
        names[i] = colon < 0 ? s : s.substring(0, colon);
        values[i] = colon < 0 ? "" : s.substring(colon + 1);
        i++;
      }
      schema = PeerTagSchema.testSchema(names);
    }
    SpanSnapshot syntheticSnapshot =
        buildSnapshot(
            resource,
            service,
            operationName,
            serviceSource,
            type,
            (short) httpStatusCode,
            synthetic,
            traceRoot,
            spanKind,
            schema,
            values,
            httpMethod,
            httpEndpoint,
            grpcStatusCode,
            0L);
    return forSnapshot(syntheticSnapshot);
  }

  /**
   * Test-only snapshot builder. Production fillSlot canonicalizes each field via the per-field
   * DDCaches on {@link AggregateEntry} and precomputes {@link SpanSnapshot#keyHash}; this helper
   * does the same so tests exercise the production matches/hash path.
   */
  static SpanSnapshot buildSnapshot(
      CharSequence resource,
      CharSequence service,
      CharSequence operationName,
      @Nullable CharSequence serviceSource,
      CharSequence type,
      short httpStatusCode,
      boolean synthetic,
      boolean traceRoot,
      CharSequence spanKind,
      @Nullable PeerTagSchema peerTagSchema,
      @Nullable String[] peerTagValues,
      @Nullable CharSequence httpMethod,
      @Nullable CharSequence httpEndpoint,
      @Nullable CharSequence grpcStatusCode,
      long tagAndDuration) {
    SpanSnapshot s = new SpanSnapshot();
    s.resourceName = AggregateEntry.canonicalize(AggregateEntry.RESOURCE_CACHE, resource);
    s.serviceName = AggregateEntry.canonicalize(AggregateEntry.SERVICE_CACHE, service);
    s.operationName = AggregateEntry.canonicalize(AggregateEntry.OPERATION_CACHE, operationName);
    s.serviceNameSource =
        AggregateEntry.canonicalizeOptional(AggregateEntry.SERVICE_SOURCE_CACHE, serviceSource);
    s.spanType = AggregateEntry.canonicalize(AggregateEntry.TYPE_CACHE, type);
    s.httpStatusCode = httpStatusCode;
    s.synthetic = synthetic;
    s.traceRoot = traceRoot;
    s.spanKind = AggregateEntry.canonicalize(AggregateEntry.SPAN_KIND_CACHE, spanKind);
    s.peerTagSchema = peerTagSchema;
    s.peerTagValues = peerTagValues;
    s.httpMethod =
        AggregateEntry.canonicalizeOptional(AggregateEntry.HTTP_METHOD_CACHE, httpMethod);
    s.httpEndpoint =
        AggregateEntry.canonicalizeOptional(AggregateEntry.HTTP_ENDPOINT_CACHE, httpEndpoint);
    s.grpcStatusCode =
        AggregateEntry.canonicalizeOptional(AggregateEntry.GRPC_STATUS_CODE_CACHE, grpcStatusCode);
    s.tagAndDuration = tagAndDuration;
    SpanSnapshot.computeAndSetKeyHash(s);
    return s;
  }

  /**
   * Builds an {@link AggregateEntry} from {@code s}. Production callers route through {@link
   * AggregateTable#findOrInsert}; tests use this helper for direct construction.
   */
  public static AggregateEntry forSnapshot(SpanSnapshot s) {
    return new AggregateEntry(s);
  }

  /**
   * Whether {@code a} and {@code b} carry identical label fields. Counter and histogram state is
   * intentionally excluded -- this compares the key identity, not the aggregate.
   */
  public static boolean equals(AggregateEntry a, AggregateEntry b) {
    if (a == b) return true;
    if (a == null || b == null) return false;
    return a.getHttpStatusCode() == b.getHttpStatusCode()
        && a.isSynthetics() == b.isSynthetics()
        && a.isTraceRoot() == b.isTraceRoot()
        && Objects.equals(a.getResource(), b.getResource())
        && Objects.equals(a.getService(), b.getService())
        && Objects.equals(a.getOperationName(), b.getOperationName())
        && Objects.equals(a.getServiceSource(), b.getServiceSource())
        && Objects.equals(a.getType(), b.getType())
        && Objects.equals(a.getSpanKind(), b.getSpanKind())
        && Arrays.equals(a.peerTagNames, b.peerTagNames)
        && Arrays.equals(a.peerTagValues, b.peerTagValues)
        && Objects.equals(a.getHttpMethod(), b.getHttpMethod())
        && Objects.equals(a.getHttpEndpoint(), b.getHttpEndpoint())
        && Objects.equals(a.getGrpcStatusCode(), b.getGrpcStatusCode());
  }

  /**
   * Stable hash matching {@link #equals(AggregateEntry, AggregateEntry)} -- derived from {@link
   * AggregateEntry#keyHash}, which {@link AggregateEntry#hashOf} computes from the same raw fields
   * the helper's {@code equals} compares.
   */
  public static int hashCode(AggregateEntry e) {
    return e == null ? 0 : (int) e.keyHash;
  }
}
