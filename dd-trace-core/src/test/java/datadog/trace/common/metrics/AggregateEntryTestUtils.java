package datadog.trace.common.metrics;

import java.util.Arrays;
import java.util.Objects;

/**
 * Field-wise equality helper for {@link AggregateEntry}, used by Spock mock argument matchers and
 * JUnit assertions. Production {@code AggregateEntry} intentionally has no {@code equals}/{@code
 * hashCode} override -- {@link AggregateTable} bucketing goes through {@link
 * AggregateEntry#matches} keyed on {@link AggregateEntry#keyHash}, and no production code path
 * invokes {@link Object#equals}.
 *
 * <p>Compares the raw {@code peerTagNames}/{@code peerTagValues} arrays (not the encoded {@code
 * peerTags} list) so the helper stays consistent with {@link AggregateEntry#hashOf}, which folds in
 * raw arrays via {@link PeerTagSchema#hashCode()} and {@link Arrays#hashCode(Object[])}. Comparing
 * the encoded list would let two entries with different raw layouts (e.g. tag {@code "b"} at index
 * 1 in schema A vs index 0 in schema B, with matching values) collapse to the same encoded form --
 * a real bug surfaced during PR #11382 review.
 */
public final class AggregateEntryTestUtils {
  private AggregateEntryTestUtils() {}

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
