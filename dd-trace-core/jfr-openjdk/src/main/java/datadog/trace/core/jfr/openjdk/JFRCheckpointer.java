package datadog.trace.core.jfr.openjdk;

import datadog.trace.api.EndpointCheckpointer;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.DDSpan;
import datadog.trace.core.EndpointTracker;
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
  public final void onRootSpanFinished(final AgentSpan rootSpan, final boolean published) {
    if (isEndpointCollectionEnabled) {
      if (rootSpan instanceof DDSpan) {
        DDSpan span = (DDSpan) rootSpan;
        EndpointTracker tracker = span.getEndpointTracker();
        if (tracker != null) {
          boolean traceSampled = published && !span.eligibleForDropping();
          tracker.endpointWritten(span, traceSampled, true);
        }
      }
    }
  }

  @Override
  public void onRootSpanStarted(AgentSpan rootSpan) {
    if (isEndpointCollectionEnabled) {
      if (rootSpan instanceof DDSpan) {
        DDSpan span = (DDSpan) rootSpan;
        span.setEndpointTracker(new EndpointEvent(span));
      }
    }
  }
}
