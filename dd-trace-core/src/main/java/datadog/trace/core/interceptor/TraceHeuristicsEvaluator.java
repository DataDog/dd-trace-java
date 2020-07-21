package datadog.trace.core.interceptor;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import datadog.trace.api.DDTags;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.HdrHistogram.Histogram;

@Slf4j
public class TraceHeuristicsEvaluator extends CacheLoader<String, Histogram>
    implements TraceInterceptor {

  Cache<String, Boolean> evaluationCache = CacheBuilder.newBuilder().maximumSize(100).build();

  LoadingCache<String, Histogram> histogramCache = CacheBuilder.newBuilder().build(this);
  // for now collect everything into a single histogram
  final Histogram overallStats = new Histogram(1);

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      final Collection<? extends MutableSpan> trace) {
    if (trace instanceof List && !trace.isEmpty()) {
      final AgentSpan firstSpan = ((List<AgentSpan>) trace).get(0);
      final AgentSpan rootSpan = firstSpan.getLocalRootSpan();
      if (!(rootSpan instanceof DDSpan && ((DDSpan) rootSpan).isFinished())) {
        // This is probably a partial flush, so skip processing.
        return trace;
      }

      try {
        final long durationNano = rootSpan.getDurationNano();
        overallStats.recordValue(durationNano);
        final String cacheKey = getCacheKey(rootSpan);
        final Histogram histogram = histogramCache.get(cacheKey);
        histogram.recordValue(durationNano);

        final boolean isDistinctive = isTraceDistinctive(cacheKey, histogram);
        evaluationCache.put(cacheKey, isDistinctive);
      } catch (final Exception e) {
        log.debug("error recording trace stats", e);
      }
    }

    return trace;
  }

  private boolean isTraceDistinctive(final String cacheKey, final Histogram traceStats) {

    final long traceAverage = traceStats.getValueAtPercentile(50);
    final long overall80 = overallStats.getValueAtPercentile(80);
    if (overall80 < traceAverage) {
      // This trace is likely to be slower than most.
      return true;
    }

    final long traceCount = traceStats.getTotalCount();
    final long overallCount = overallStats.getTotalCount();
    if (3 < traceCount && traceCount < (overallCount * .9)) {
      // This is an uncommon trace (but not unique).
      return true;
    }

    return false;
  }

  public boolean isDistinctive(final AgentSpan currentSpan) {
    final AgentSpan rootSpan = currentSpan.getLocalRootSpan();
    final Boolean isDistinctive =
        rootSpan == null ? null : evaluationCache.getIfPresent(getCacheKey(rootSpan));
    return isDistinctive == null ? false : isDistinctive;
  }

  private String getCacheKey(final AgentSpan span) {
    final Map<String, Object> tags = span.getTags();
    Object value = tags.get(DDTags.RESOURCE_NAME);
    if (value != null) {
      return value.toString();
    }
    value = tags.get(Tags.HTTP_URL);
    if (value != null) {
      // Do we want to apply normalization here?
      return value.toString();
    }
    value = tags.get(Tags.DB_STATEMENT);
    if (value != null) {
      // Unlikely to give consistent results... maybe we should use instance instead?
      return value.toString();
    }
    // TODO add class/method after those are added as tags.
    return span.getResourceName().toString();
  }

  @Override
  public Histogram load(final String key) {
    return new Histogram(1);
  }

  @Override
  public int priority() {
    // Implied since manually processed after user provided interceptors.
    return Integer.MAX_VALUE;
  }
}
