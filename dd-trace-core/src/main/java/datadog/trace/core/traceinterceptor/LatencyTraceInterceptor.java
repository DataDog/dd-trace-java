package datadog.trace.core.traceinterceptor;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.api.interceptor.AbstractTraceInterceptor;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import java.util.Collection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LatencyTraceInterceptor extends AbstractTraceInterceptor {
  private static final Logger log = LoggerFactory.getLogger(LatencyTraceInterceptor.class);
  // duration configured in ms, need to be converted in nano seconds
  private static final int LATENCY = Config.get().getTraceLatencyInterceptorValue() * 1000000;

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
