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
 * datadog.trace.common.metrics} package so this helper can reach package-private members.
 *
 * <p>Production {@code AggregateEntry} intentionally has no {@code equals}/{@code hashCode}
 * override -- {@link AggregateTable} bucketing goes through the {@code Canonical} scratch buffer
 * keyed on {@link AggregateEntry#keyHash}, and no production code path invokes {@link
 * Object#equals}.
 *
 * <p>On this branch, peer tags live as a pre-encoded {@code UTF8BytesString[]} on the entry
 * (canonicalization through {@link PeerTagSchema#register} already collapsed identical values), so
 * equality compares the arrays via {@link Arrays#equals(Object[], Object[])}. The hash side
 * (computed in {@link AggregateEntry#hashOf}) folds in the same array, so the contract stays
 * consistent.
 */
public final class AggregateEntryTestUtils {
  private AggregateEntryTestUtils() {}

  /**
   * Builds an {@link AggregateEntry} from the same positional shape the prior {@code new
   * MetricKey(...)} took. Passes through to {@link AggregateEntry#of} without touching the
   * cardinality handlers.
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
    return AggregateEntry.of(
        resource,
        service,
        operationName,
        serviceSource,
        type,
        httpStatusCode,
        synthetic,
        traceRoot,
        spanKind,
        peerTags,
        httpMethod,
        httpEndpoint,
        grpcStatusCode);
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
        && Arrays.equals(a.getPeerTags(), b.getPeerTags())
        && Objects.equals(a.getHttpMethod(), b.getHttpMethod())
        && Objects.equals(a.getHttpEndpoint(), b.getHttpEndpoint())
        && Objects.equals(a.getGrpcStatusCode(), b.getGrpcStatusCode());
  }

  /**
   * Stable hash matching {@link #equals(AggregateEntry, AggregateEntry)} -- derived from {@link
   * AggregateEntry#keyHash}, which {@link AggregateEntry#hashOf} computes from the same fields the
   * helper's {@code equals} compares.
   */
  public static int hashCode(AggregateEntry e) {
    return e == null ? 0 : (int) e.keyHash;
  }
}
