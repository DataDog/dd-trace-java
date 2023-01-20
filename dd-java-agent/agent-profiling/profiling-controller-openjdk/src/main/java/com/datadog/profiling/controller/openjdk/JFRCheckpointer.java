package com.datadog.profiling.controller.openjdk;

import com.datadog.profiling.controller.openjdk.events.EndpointEvent;
import com.datadog.profiling.utils.ExcludedVersions;
import datadog.trace.api.EndpointCheckpointer;
import datadog.trace.api.EndpointTracker;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jdk.jfr.EventType;

public class JFRCheckpointer implements EndpointCheckpointer {

  private final boolean isEndpointCollectionEnabled;

  public JFRCheckpointer() {
    this(ConfigProvider.getInstance());
  }

  JFRCheckpointer(ConfigProvider configProvider) {
    ExcludedVersions.checkVersionExclusion();
    // Note: Loading CheckpointEvent when JFRCheckpointer is loaded is important because it also
    // loads JFR classes - which may not be present on some JVMs
    EventType.getEventType(EndpointEvent.class);

    isEndpointCollectionEnabled =
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED,
            ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED_DEFAULT);
  }

  @Override
  public final void onRootSpanFinished(final AgentSpan rootSpan, EndpointTracker tracker) {
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
}
