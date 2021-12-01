package datadog.trace;

import com.google.auto.service.AutoService;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import java.util.Collection;

@AutoService(TraceInterceptor.class)
public class TestInterceptor implements TraceInterceptor {
  // We set a high priority to avoid competing with real TraceInterceptors.
  public static volatile int priority = 999;

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      final Collection<? extends MutableSpan> trace) {
    return trace;
  }

  @Override
  public int priority() {
    return priority;
  }
}
