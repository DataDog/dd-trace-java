package com.datadog.profiling.jfr.context;

import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.core.DDSpanContext;
import jdk.jfr.ContextAccess;

public final class ContextIntegration {

  public static void initialize() {
    DDSpanContext.profilingContextAccess = new ProfilerContext.Access() {
      private final ContextAccess access = ContextAccess.forType(DDSpanContext.class);
      @Override
      public void set(ProfilerContext ctx) {
        access.set(ctx);
      }

      @Override
      public void unset() {
        access.unset();
      }
    };
  }
}
