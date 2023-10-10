package datadog.trace.bootstrap.instrumentation.api;

import datadog.trace.api.profiling.ProfilingContext;

public interface ProfilerContext {
  interface Access {
    Access NOOP = new Access() {
      @Override
      public void set(ProfilerContext ctx) {}

      @Override
      public void unset() {}

      @Override
      public String toString() {
        return "NOOP";
      }
    };

    void set(ProfilerContext ctx);
    void unset();
  }

  long getSpanId();

  /** @return the span id of the local root span, or the span itself */
  long getRootSpanId();

  int getEncodedOperationName();
}
