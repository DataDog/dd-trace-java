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
        public boolean addTraceInterceptor(TraceInterceptor traceInterceptor) {
          return false;
        }

        @Override
        public void addScopeListener(ScopeListener listener) {}
      };
  private static final AtomicReference<Tracer> provider = new AtomicReference<>(NO_OP);

  public static void registerIfAbsent(Tracer p) {
    if (p != null && p != NO_OP) {
      boolean installed = provider.compareAndSet(NO_OP, p);
      if (installed) {
        Callback callback = installationCallback.getAndSet(null);
        if (callback != null) {
          callback.installed(p);
        }
      }
    }
  }

  public static Tracer get() {
    return provider.get();
  }

  // --------------------------------------------------------------------------------
  // All code below is to support the callback registration in WithGlobalTracer
  // --------------------------------------------------------------------------------

  // Needs to use a read that can't be reordered for the code in WithGlobalTracer to be correct
  static boolean isTracerInstalled() {
    return provider.get() != NO_OP;
  }

  private static final AtomicReference<Callback> installationCallback = new AtomicReference<>(null);

  // Needs to use a read that can't be reordered for the code in WithGlobalTracer to be correct
  static boolean isCallbackInstalled() {
    return installationCallback.get() != null;
  }

  static boolean registerInstallationCallback(Callback callback) {
    if (!isTracerInstalled()) {
      boolean installed = installationCallback.compareAndSet(null, callback);
      // Check if the tracer was installed while we were doing this, and try to back out
      if (installed && isTracerInstalled()) {
        installed = !installationCallback.compareAndSet(callback, null);
      }
      return installed;
    } else {
      return false;
    }
  }

  interface Callback {
    void installed(Tracer tracer);
  }
}
