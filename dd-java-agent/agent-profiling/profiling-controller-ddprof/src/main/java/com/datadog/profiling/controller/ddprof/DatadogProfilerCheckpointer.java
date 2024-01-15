package com.datadog.profiling.controller.ddprof;

import com.datadog.profiling.ddprof.DatadogProfiler;
import datadog.trace.api.EndpointCheckpointer;
import datadog.trace.api.EndpointTracker;
import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.bootstrap.config.provider.ConfigProvider;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public class DatadogProfilerCheckpointer implements EndpointCheckpointer {

  private final DatadogProfiler datadogProfiler;
  private final boolean isEndpointCollectionEnabled;

  public DatadogProfilerCheckpointer(
      DatadogProfiler datadogProfiler, ConfigProvider configProvider) {
    this.datadogProfiler = datadogProfiler;
    this.isEndpointCollectionEnabled =
        configProvider.getBoolean(
            ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED,
            ProfilingConfig.PROFILING_ENDPOINT_COLLECTION_ENABLED_DEFAULT);
  }

  public DatadogProfilerCheckpointer() {
    this(DatadogProfiler.getInstance(), ConfigProvider.getInstance());
  }

  @Override
  public void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker) {
    if (isEndpointCollectionEnabled && rootSpan != null) {
      CharSequence resourceName = rootSpan.getResourceName();
      CharSequence operationName = rootSpan.getOperationName();
      if (resourceName != null && operationName != null) {
        datadogProfiler.recordTraceRoot(
            rootSpan.getSpanId(), resourceName.toString(), operationName.toString());
      }
    }
  }

  @Override
  public EndpointTracker onRootSpanStarted(AgentSpan rootSpan) {
    return NoOpEndpointTracker.INSTANCE;
  }

  /**
   * This implementation is actually stateless, so we don't actually need a tracker object, but
   * we'll create a singleton to avoid returning null and risking NPEs elsewhere.
   */
  private static final class NoOpEndpointTracker implements EndpointTracker {

    public static final NoOpEndpointTracker INSTANCE = new NoOpEndpointTracker();

    @Override
    public void endpointWritten(AgentSpan span) {}
  }
}
