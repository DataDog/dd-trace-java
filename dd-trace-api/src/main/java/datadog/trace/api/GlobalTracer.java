package datadog.trace.api;

import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.context.ScopeListener;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A global reference to the registered Datadog tracer.
 *
 * <p>OpenTracing's GlobalTracer cannot be cast to its DDTracer implementation, so this class exists
 * to provide a global window to datadog-specific features.
 */
public class GlobalTracer {
  private static final Tracer NO_OP =
      new Tracer() {
        @Override
        public String getTraceId() {
          return "0";
        }

        @Override
        public String getSpanId() {
          return "0";
        }

        @Override
        public boolean addTraceInterceptor(final TraceInterceptor traceInterceptor) {
          return false;
        }

        @Override
        public void addScopeListener(final ScopeListener listener) {}
      };
  private static final AtomicReference<Tracer> provider = new AtomicReference<>(NO_OP);

  public static void registerIfAbsent(final Tracer p) {
    if (p != null && p != NO_OP) {
      provider.compareAndSet(NO_OP, p);
    }
  }

  public static void replace(final Tracer p) {
    provider.set(p);
  }

  public static Tracer get() {
    return provider.get();
  }
}
