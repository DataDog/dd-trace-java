package datadog.trace.core.interceptor;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import datadog.trace.api.DDTags;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.HdrHistogram.Histogram;

@Slf4j
public class TraceStatsCollector extends CacheLoader<String, Histogram>
    implements TraceInterceptor {
  LoadingCache<String, Histogram> cache = CacheBuilder.newBuilder().build(this);
  // for now collect everything into a single histogram
  final Histogram overallStats = new Histogram(1);

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      final Collection<? extends MutableSpan> trace) {
    if (trace instanceof List && !trace.isEmpty()) {
      final MutableSpan firstSpan = ((List<MutableSpan>) trace).get(0);
      final MutableSpan rootSpan = firstSpan.getLocalRootSpan();
      if (!(rootSpan instanceof DDSpan && ((DDSpan) rootSpan).isFinished())) {
        // This is probably a partial flush, so skip processing.
        return trace;
      }

      try {
        final long durationNano = rootSpan.getDurationNano();
        overallStats.recordValue(durationNano);
        final Histogram histogram = cache.get(getCacheKey(rootSpan));
        histogram.recordValue(durationNano);
      } catch (final Exception e) {
        log.debug("error recording trace stats", e);
      }
    }

    return trace;
  }

  public Histogram getTraceStats(final DDSpan currentSpan) {
    final DDSpan rootSpan = currentSpan.getLocalRootSpan();
    return rootSpan == null ? null : cache.getIfPresent(getCacheKey(rootSpan));
  }

  public Histogram getOverallStats() {
    return overallStats;
  }

  private String getCacheKey(final MutableSpan span) {
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
    return span.getResourceName();
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
