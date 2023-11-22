package com.datadog.profiling.controller.openjdk;

import com.datadog.profiling.controller.openjdk.events.TimelineEvent;
import datadog.trace.api.Stateful;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

public class JFREventContextIntegration implements ProfilingContextIntegration {

  @Override
  public Stateful newScopeState(ProfilerContext profilerContext) {
    return new TimelineEvent(
        profilerContext.getRootSpanId(),
        profilerContext.getSpanId(),
        String.valueOf(profilerContext.getOperationName()));
  }

  @Override
  public String name() {
    return "jfr";
  }
}
