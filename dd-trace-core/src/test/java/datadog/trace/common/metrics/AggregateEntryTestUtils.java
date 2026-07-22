package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.Collections;
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
 * <p>Peer tags live as a single pre-encoded {@code List<UTF8BytesString>} on the entry
 * (canonicalization through {@link PeerTagSchema#register} already collapsed identical values), so
 * equality compares the list directly. The hash side (computed in {@link AggregateEntry#hashOf})
 * folds in the encoded list, so the contract stays consistent.
 */
public final class AggregateEntryTestUtils {
  private AggregateEntryTestUtils() {}

  /**
   * Builds an {@link AggregateEntry} from positional args. Bypasses the cardinality handlers so
   * tests can create expected values without mutating shared handler state. Content-equal entries
   * from {@link AggregateEntry.Canonical#createEntry} still compare equal via {@link
   * #equals(AggregateEntry, AggregateEntry)}.
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
    UTF8BytesString resourceUtf = AggregateEntry.createUtf8(resource);
    UTF8BytesString serviceUtf = AggregateEntry.createUtf8(service);
    UTF8BytesString operationNameUtf = AggregateEntry.createUtf8(operationName);
    UTF8BytesString serviceSourceUtf = AggregateEntry.createUtf8(serviceSource);
    UTF8BytesString typeUtf = AggregateEntry.createUtf8(type);
    UTF8BytesString spanKindUtf = AggregateEntry.createUtf8(spanKind);
    UTF8BytesString httpMethodUtf = AggregateEntry.createUtf8(httpMethod);
    UTF8BytesString httpEndpointUtf = AggregateEntry.createUtf8(httpEndpoint);
    UTF8BytesString grpcUtf = AggregateEntry.createUtf8(grpcStatusCode);
    List<UTF8BytesString> peerTagsList = peerTags == null ? Collections.emptyList() : peerTags;
    UTF8BytesString[] peerTagsArr = peerTagsList.toArray(new UTF8BytesString[0]);
    UTF8BytesString[] emptyAdditional = new UTF8BytesString[0];
    long keyHash =
        AggregateEntry.hashOf(
            resourceUtf,
            serviceUtf,
            operationNameUtf,
            serviceSourceUtf,
            typeUtf,
            spanKindUtf,
            httpMethodUtf,
            httpEndpointUtf,
            grpcUtf,
            (short) httpStatusCode,
            synthetic,
            traceRoot,
            peerTagsArr,
            peerTagsArr.length,
            emptyAdditional,
            0);
    return new AggregateEntry(
        keyHash,
        resourceUtf,
        serviceUtf,
        operationNameUtf,
        serviceSourceUtf,
        typeUtf,
        spanKindUtf,
        httpMethodUtf,
        httpEndpointUtf,
        grpcUtf,
        (short) httpStatusCode,
        synthetic,
        traceRoot,
        peerTagsList,
        emptyAdditional);
  }

  /**
   * Records one OK hit of {@code durationNanos} on {@code e}. Exposes the package-private {@link
   * AggregateEntry#recordOneDuration} to tests in other packages (e.g. the OTLP metrics writer
   * tests) without widening the production mutation API.
   */
  public static AggregateEntry recordOk(AggregateEntry e, long durationNanos) {
    return e.recordOneDuration(durationNanos);
  }

  /** Records one error hit of {@code durationNanos} on {@code e}. See {@link #recordOk}. */
  public static AggregateEntry recordError(AggregateEntry e, long durationNanos) {
    return e.recordOneDuration(durationNanos | AggregateEntry.ERROR_TAG);
  }

  /** Records one top-level OK hit of {@code durationNanos} on {@code e}. See {@link #recordOk}. */
  public static AggregateEntry recordTopLevel(AggregateEntry e, long durationNanos) {
    return e.recordOneDuration(durationNanos | AggregateEntry.TOP_LEVEL_TAG);
  }

  /** Clears the per-cycle counters and histograms on {@code e}. See {@link #recordOk}. */
  public static void clear(AggregateEntry e) {
    e.clearAggregate();
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
        && a.getPeerTags().equals(b.getPeerTags())
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
