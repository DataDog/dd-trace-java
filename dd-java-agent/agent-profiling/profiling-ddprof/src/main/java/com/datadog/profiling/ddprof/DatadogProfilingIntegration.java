package com.datadog.profiling.ddprof;

import datadog.trace.bootstrap.instrumentation.api.ProfilerContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

/**
 * This class must be installed early to be able to see all scope initialisations, which means it
 * must not be modified to depend on JFR, so that it can be installed before tracing starts.
 */
public class DatadogProfilingIntegration implements ProfilingContextIntegration {

  private static final DatadogProfiler DDPROF = DatadogProfiler.getInstance();
  private static final int SPAN_NAME_INDEX = DDPROF.operationNameOffset();

  private static final int RESOURCE_NAME_INDEX = DDPROF.resourceNameOffset();

  private static final int SERVICE_NAME_INDEX = DDPROF.serviceNameOffset();
  private static final boolean WALLCLOCK_ENABLED =
      DatadogProfilerConfig.isWallClockProfilerEnabled();

  private static final boolean QUEUEING_TIME_ENABLED =
      WALLCLOCK_ENABLED && DatadogProfilerConfig.isQueueingTimeEnabled();

  @Override
  public void onAttach() {
    if (WALLCLOCK_ENABLED) {
      DDPROF.addThread();
    }
  }

  @Override
  public void onDetach() {
    if (WALLCLOCK_ENABLED) {
      DDPROF.removeThread();
    }
  }

  @Override
  public int encode(CharSequence constant) {
    return DDPROF.encode(constant);
  }

  @Override
  public void setContext(ProfilerContext profilerContext) {
    DDPROF.setSpanContext(profilerContext.getSpanId(), profilerContext.getRootSpanId());
    DDPROF.setContextValue(RESOURCE_NAME_INDEX, profilerContext.getEncodedResourceName());
    DDPROF.setContextValue(SPAN_NAME_INDEX, profilerContext.getEncodedOperationName());
    DDPROF.setContextValue(SERVICE_NAME_INDEX, profilerContext.getEncodedServiceName());
  }

  @Override
  public void clearContext() {
    DDPROF.clearSpanContext();
    DDPROF.clearContextValue(RESOURCE_NAME_INDEX);
    DDPROF.clearContextValue(SPAN_NAME_INDEX);
    DDPROF.clearContextValue(SERVICE_NAME_INDEX);
  }

  @Override
  public void setContext(long rootSpanId, long spanId) {
    DDPROF.setSpanContext(spanId, rootSpanId);
  }

  @Override
  public void setContextValue(String attribute, String value) {
    DDPROF.setContextValue(attribute, value);
  }

  @Override
  public void clearContextValue(String attribute) {
    DDPROF.clearContextValue(attribute);
  }

  @Override
  public boolean isQueuingTimeEnabled() {
    return QUEUEING_TIME_ENABLED;
  }

  @Override
  public void recordQueueingTime(long duration) {}
}
