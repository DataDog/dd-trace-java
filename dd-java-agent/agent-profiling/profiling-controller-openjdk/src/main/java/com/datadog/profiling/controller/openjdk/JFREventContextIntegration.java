package com.datadog.profiling.controller.openjdk;

import com.datadog.profiling.controller.openjdk.events.TimelineEvent;
import com.datadog.profiling.utils.ExcludedVersions;
import datadog.trace.api.Platform;
import datadog.trace.api.Stateful;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

public class JFREventContextIntegration implements ProfilingContextIntegration {

  public JFREventContextIntegration() {
    ExcludedVersions.checkVersionExclusion();
  }

  // native image process will enable context integration immediately - the value will get
  // 'baked-in' during image build time
  // java agent process will enable context integration when the agent is started and onStart is
  // called
  private volatile boolean isStarted = !Platform.isNativeImageBuilder();

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
