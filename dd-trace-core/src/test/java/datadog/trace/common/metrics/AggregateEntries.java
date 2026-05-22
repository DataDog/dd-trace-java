package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Test-side factories for {@link AggregateEntry}. Lives in {@code src/test} so the production class
 * stays free of test-only API; same {@code datadog.trace.common.metrics} package so this helper can
 * reach {@link AggregateEntry#of} directly.
 */
public final class AggregateEntries {
  private AggregateEntries() {}

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
}
