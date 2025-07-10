package datadog.smoketest.opentracing;

import datadog.trace.api.Tracer;
import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
import java.util.Collection;

public final class ApiVerification {
  private ApiVerification() {}

  public static void verifyInterceptors(Tracer otTracer, boolean throwError) {
    TraceInterceptor interceptor =
        new TraceInterceptor() {
          @Override
          public Collection<? extends MutableSpan> onTraceComplete(
              Collection<? extends MutableSpan> trace) {
            // Emulates situation when user code will throw an Error.
            if (throwError) {
              throw new AssertionError();
            }

            return trace;
          }

          @Override
          public int priority() {
            return 1729;
          }
        };

    otTracer.addTraceInterceptor(interceptor);
  }
}
