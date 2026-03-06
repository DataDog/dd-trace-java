package datadog.trace.api;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.api.interceptor.TraceInterceptor;
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

        @Override
        public Blackhole muteTracing() {
          return NO_OP_BLACKHOLE;
        }

        @Override
        public MutableSpan getActiveMutableSpan() {
          return null;
        }

        @Override
        public MutableSpan getLocalRootSpan() {
          return null;
        }

        @Override
        public MutableSpan getLocalRootSpan(MutableSpan mutableSpan) {
          return null;
        }

        @Override
        public MutableSpan toMutableSpan(Object span) throws IllegalArgumentException {
          return null;
        }
      };

  private static final Tracer.Blackhole NO_OP_BLACKHOLE = () -> {};

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

  /**
   * @deprecated use static methods in {@link EventTrackerV2} directly
   */
  @Deprecated
  public static EventTracker getEventTracker() {
    return eventTracker;
  }

  /**
   * Controls the implementation for the event tracker. The AppSec subsystem calls this method on
   * startup. This can be called explicitly for e.g. testing purposes.
   *
   * @param tracker the implementation for the event tracker.
   */
  public static void setEventTracker(final EventTracker tracker) {
    eventTracker = tracker;
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
