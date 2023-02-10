package datadog.trace.api;

import datadog.trace.api.interceptor.TraceInterceptor;
import datadog.trace.api.internal.InternalTracer;
import java.util.ArrayList;
import java.util.Collection;

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
      };

  private static final Collection<Callback> installationCallbacks = new ArrayList<>();
  private static Tracer provider = NO_OP;
  private static EventTracker eventTracker = EventTracker.NO_EVENT_TRACKER;

  public static void registerIfAbsent(Tracer p) {
    if (p == null || p == NO_OP) {
      throw new IllegalArgumentException();
    }

    synchronized (installationCallbacks) {
      if (provider == NO_OP) {
        provider = p;
        for (Callback callback : installationCallbacks) {
          callback.installed(p);
        }
      }
    }
  }

  public static void forceRegister(Tracer tracer) {
    if (tracer == null || tracer == NO_OP) {
      throw new IllegalArgumentException();
    }

    synchronized (installationCallbacks) {
      provider = tracer;
      for (Callback callback : installationCallbacks) {
        callback.installed(tracer);
      }
    }
  }

  public static Tracer get() {
    return provider;
  }

  public static EventTracker getEventTracker() {
    if (eventTracker == EventTracker.NO_EVENT_TRACKER) {
      if (provider instanceof InternalTracer) {
        eventTracker = new EventTracker((InternalTracer) provider);
      }
    }
    return eventTracker;
  }

  // --------------------------------------------------------------------------------
  // All code below is to support the callback registration in WithGlobalTracer
  // --------------------------------------------------------------------------------
  static void registerInstallationCallback(Callback callback) {
    synchronized (installationCallbacks) {
      installationCallbacks.add(callback);

      if (provider != NO_OP) {
        callback.installed(provider);
      }
    }
  }

  interface Callback {
    void installed(Tracer tracer);
  }
}
