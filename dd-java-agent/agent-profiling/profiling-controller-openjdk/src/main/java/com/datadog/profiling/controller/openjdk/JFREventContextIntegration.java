package com.datadog.profiling.controller.openjdk;

import com.datadog.profiling.controller.openjdk.events.TimelineEvent;
import com.datadog.profiling.utils.ExcludedVersions;
import datadog.trace.api.Stateful;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

public class JFREventContextIntegration implements ProfilingContextIntegration {

  public JFREventContextIntegration() {
    ExcludedVersions.checkVersionExclusion();
  }

  private volatile boolean isStarted = false;

  @Override
  public void onStart() {
    // avoid initialising JFR until called
    isStarted = true;
  }

  @Override
  public Stateful newScopeState(ProfilerContext profilerContext) {
    if (!isStarted) {
      return Stateful.DEFAULT;
    }
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
