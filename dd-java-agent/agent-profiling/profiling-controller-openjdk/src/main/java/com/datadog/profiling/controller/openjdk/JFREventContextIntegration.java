package com.datadog.profiling.controller.openjdk;

import com.datadog.profiling.controller.openjdk.events.EndpointEvent;
import com.datadog.profiling.controller.openjdk.events.QueueTimeEvent;
import com.datadog.profiling.controller.openjdk.events.TimelineEvent;
import com.datadog.profiling.utils.ExcludedVersions;
import datadog.trace.api.EndpointTracker;
import datadog.trace.api.Platform;
import datadog.trace.api.Stateful;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.Timing;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import jdk.jfr.EventType;

public class JFREventContextIntegration implements ProfilingContextIntegration {

  public JFREventContextIntegration() {
    ExcludedVersions.checkVersionExclusion();
    // Note: Loading CheckpointEvent when JFRCheckpointer is loaded is important because it also
    // loads JFR classes - which may not be present on some JVMs
    EventType.getEventType(EndpointEvent.class);
    isEndpointCollectionEnabled =
        ConfigProvider.getInstance()
            .getBoolean(
                ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED,
                ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED_DEFAULT);
    isTimelineEventsEnabled =
        ConfigProvider.getInstance()
            .getBoolean(
                ProfilingConfig.PROFILING_TIMELINE_EVENTS_ENABLED,
                ProfilingConfig.PROFILING_TIMELINE_EVENTS_ENABLED_DEFAULT);
    isQueueTimeEnabled =
        ConfigProvider.getInstance()
            .getBoolean(
                ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED,
                ProfilingConfig.PROFILING_QUEUEING_TIME_ENABLED_DEFAULT);
  }

  // native image process will enable context integration immediately - the value will get
  // 'baked-in' during image build time
  // java agent process will enable context integration when the agent is started and onStart is
  // called
  private volatile boolean isStarted = !Platform.isNativeImageBuilder();
  private final boolean isEndpointCollectionEnabled;
  private final boolean isTimelineEventsEnabled;
  private final boolean isQueueTimeEnabled;

  @Override
  public void onStart() {
    // avoid initialising JFR until called
    isStarted = true;
  }

  @Override
  public Stateful newScopeState(ProfilerContext profilerContext) {
    if (!isTimelineEventsEnabled || !isStarted) {
      return Stateful.DEFAULT;
    }
    return new TimelineEvent.Holder(
        profilerContext.getRootSpanId(),
        profilerContext.getSpanId(),
        String.valueOf(profilerContext.getOperationName()));
  }

  @Override
  public String name() {
    return "jfr";
  }

  @Override
  public void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker) {
    if (isEndpointCollectionEnabled && tracker != null) {
      tracker.endpointWritten(rootSpan);
    }
  }

  @Override
  public EndpointTracker onRootSpanStarted(AgentSpan rootSpan) {
    return isEndpointCollectionEnabled
        ? new EndpointEvent(rootSpan.getSpanId())
        : EndpointTracker.NO_OP;
  }

  @Override
  public Timing start(TimerType type) {
    if (isQueueTimeEnabled && type == TimerType.QUEUEING) {
      return new QueueTimeEvent();
    }
    return Timing.NoOp.INSTANCE;
  }
}
