package datadog.trace.common.metrics;

/**
 * InboxItem that carries extracted span data from the foreground thread to the background
 * aggregator thread. The expensive MetricKey construction, HashMap operations, and Batch management
 * happen on the background thread after receiving this item.
 */
final class TraceStatsData implements InboxItem {
  final SpanStatsData[] spans;
  final int totalSpanCount;
  final boolean hasError;

  TraceStatsData(SpanStatsData[] spans, int totalSpanCount, boolean hasError) {
    this.spans = spans;
    this.totalSpanCount = totalSpanCount;
    this.hasError = hasError;
  }
}
