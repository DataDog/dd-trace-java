package datadog.trace.common.metrics;

import static datadog.trace.api.Functions.UTF8_ENCODE;
import static datadog.trace.common.metrics.ConflatingMetricsAggregator.PEER_TAGS_CACHE;
import static datadog.trace.common.metrics.ConflatingMetricsAggregator.PEER_TAGS_CACHE_ADDER;
import static datadog.trace.common.metrics.ConflatingMetricsAggregator.SERVICE_NAMES;
import static datadog.trace.common.metrics.ConflatingMetricsAggregator.SPAN_KINDS;

import datadog.trace.api.Pair;
import datadog.trace.api.cache.DDCache;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * Canonicalization helpers for {@link MetricKey}: applies the static {@link
 * ConflatingMetricsAggregator#SERVICE_NAMES} / {@link ConflatingMetricsAggregator#SPAN_KINDS} /
 * {@link ConflatingMetricsAggregator#PEER_TAGS_CACHE} caches to a {@link SpanSnapshot}.
 *
 * <p>Called only on a true miss in {@link AggregateTable}, so the CHM lookups inside the DDCaches
 * happen once per unique key rather than once per snapshot.
 */
final class MetricKeys {
  private MetricKeys() {}

  static MetricKey fromSnapshot(SpanSnapshot s) {
    return new MetricKey(
        s.resourceName,
        SERVICE_NAMES.computeIfAbsent(s.serviceName, UTF8_ENCODE),
        s.operationName,
        s.serviceNameSource,
        s.spanType,
        s.httpStatusCode,
        s.synthetic,
        s.traceRoot,
        SPAN_KINDS.computeIfAbsent(s.spanKind, UTF8BytesString::create),
        materializePeerTags(s.peerTagPairs),
        s.httpMethod,
        s.httpEndpoint,
        s.grpcStatusCode);
  }

  private static List<UTF8BytesString> materializePeerTags(String[] pairs) {
    if (pairs == null || pairs.length == 0) {
      return Collections.emptyList();
    }
    if (pairs.length == 2) {
      // single-entry fast path (matches the original singletonList shape for INTERNAL spans)
      return Collections.singletonList(encodePeerTag(pairs[0], pairs[1]));
    }
    List<UTF8BytesString> tags = new ArrayList<>(pairs.length / 2);
    for (int i = 0; i < pairs.length; i += 2) {
      tags.add(encodePeerTag(pairs[i], pairs[i + 1]));
    }
    return tags;
  }

  private static UTF8BytesString encodePeerTag(String name, String value) {
    final Pair<DDCache<String, UTF8BytesString>, Function<String, UTF8BytesString>>
        cacheAndCreator = PEER_TAGS_CACHE.computeIfAbsent(name, PEER_TAGS_CACHE_ADDER);
    return cacheAndCreator.getLeft().computeIfAbsent(value, cacheAndCreator.getRight());
  }
}
