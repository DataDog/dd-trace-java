package datadog.trace.common.metrics;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Test-side factories for {@link AggregateEntry}. Lives in {@code src/test} so the production class
 * stays free of test-only API; same {@code datadog.trace.common.metrics} package so this helper can
 * reach {@link AggregateEntry#forSnapshot(SpanSnapshot)} and the package-private {@link
 * SpanSnapshot} constructor.
 */
public final class AggregateEntries {
  private AggregateEntries() {}

  /**
   * Builds an {@link AggregateEntry} from the same positional shape the prior {@code new
   * MetricKey(...)} took. Accepts a pre-encoded {@code List<UTF8BytesString>} of {@code
   * "name:value"} peer tags and recovers the parallel-array {@code (names, values)} form by
   * splitting on the {@code ':'} delimiter.
   *
   * <p><b>Test-only.</b> The split is at the <em>first</em> {@code ':'}, so peer-tag values
   * containing a colon (URLs, IPv6 addresses, {@code service:env} patterns) will be silently
   * misparsed and the recovered (name, value) pair will be wrong. Keep test data colon-free in
   * peer-tag values, or wire a production-style snapshot through {@link
   * AggregateEntry#forSnapshot(SpanSnapshot)} directly instead.
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
        new SpanSnapshot(
            resource,
            service == null ? null : service.toString(),
            operationName,
            serviceSource,
            type,
            (short) httpStatusCode,
            synthetic,
            traceRoot,
            spanKind == null ? null : spanKind.toString(),
            schema,
            values,
            httpMethod == null ? null : httpMethod.toString(),
            httpEndpoint == null ? null : httpEndpoint.toString(),
            grpcStatusCode == null ? null : grpcStatusCode.toString(),
            0L);
    return AggregateEntry.forSnapshot(syntheticSnapshot);
  }
}
