package datadog.trace.bootstrap.aot;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import java.util.Collection;

/** Placeholder {@link TraceInterceptor} used to temporarily work around an AOT bug. */
public final class PlaceholderTraceInterceptor implements TraceInterceptor {
  public static final PlaceholderTraceInterceptor INSTANCE = new PlaceholderTraceInterceptor();

  private PlaceholderTraceInterceptor() {}

  @Override
  public Collection<? extends MutableSpan> onTraceComplete(
      Collection<? extends MutableSpan> trace) {
    return trace; // maintain original trace
  }

  @Override
  public int priority() {
    return 0;
  }
}
