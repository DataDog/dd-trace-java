package datadog.trace.core.traceinterceptor;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.interceptor.AbstractTraceInterceptor;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This trace latency interceptor is disabled by default. We can activate it by setting the value of
 * dd.trace.latency.interceptor.value to a positive value This value should be in milliseconds and
 * this interceptor will retain any local trace who has a root span duration greater than this
 * value. The activation of this interceptor is ignored if partial flush is enabled in order to
 * avoid incomplete local trace (incomplete chunk of trace). Note that since we're changing the
 * sampling priority at the end of local trace, there is no guarantee to get complete traces, since
 * the original sampling priority for this trace may have already been propagated.
 */
public class LatencyTraceInterceptor extends AbstractTraceInterceptor {
  private static final Logger log = LoggerFactory.getLogger(LatencyTraceInterceptor.class);
  // duration configured in ms, need to be converted in nano seconds
  private static final long LATENCY = Config.get().getTraceKeepLatencyThreshold() * 1000000L;

  public static final TraceInterceptor INSTANCE =
      new LatencyTraceInterceptor(Priority.ROOT_SPAN_LATENCY);

  protected LatencyTraceInterceptor(Priority priority) {
    super(priority);
  }

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      Collection<? extends MutableSpan> latencyTrace) {
    if (latencyTrace.isEmpty()) {
      return latencyTrace;
    }
    MutableSpan rootSpan = latencyTrace.iterator().next().getLocalRootSpan();
    if (rootSpan != null && rootSpan.getDurationNano() > LATENCY) {
      rootSpan.setTag(DDTags.MANUAL_KEEP, true);
    }
    return latencyTrace;
  }
}
