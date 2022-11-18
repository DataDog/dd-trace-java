package datadog.smoketest.opentracing;

import datadog.trace.api.Tracer;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import java.util.Collection;

public final class ApiVerification {
  private ApiVerification() {}

  public static void verifyInterceptors(Tracer otTracer) {
    TraceInterceptor interceptor =
        new TraceInterceptor() {
          @Override
          public Collection<? extends MutableSpan> onTraceComplete(
              Collection<? extends MutableSpan> trace) {
            return trace;
          }

          @Override
          public int priority() {
            return 1729;
          }
        };

    ((datadog.trace.api.Tracer) otTracer).addTraceInterceptor(interceptor);
  }
}
