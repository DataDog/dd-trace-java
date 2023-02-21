package com.datadog.profiling.ddprof;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContext;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

/**
 * This class must be installed early to be able to see all scope initialisations, which means it
 * must not be modified to depend on JFR, so that it can be installed before tracing starts.
 */
public class DatadogProfilingIntegration implements ProfilingContextIntegration {

  private static final int[] EMPTY_TAGS = new int[0];

  private static final DatadogProfiler DDPROF = DatadogProfiler.getInstance();
  private static final boolean WALLCLOCK_ENABLED =
      DatadogProfilerConfig.isWallClockProfilerEnabled();

  private static final boolean QUEUEING_TIME_ENABLED =
      WALLCLOCK_ENABLED && DatadogProfilerConfig.isQueueingTimeEnabled();

  private static final boolean SPAN_NAME_ATTRIBUTED_ENABLED =
      DatadogProfilerConfig.isSpanNameContextAttributeEnabled();

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
  public void setContext(long rootSpanId, long spanId) {
    DDPROF.setContext(spanId, rootSpanId);
  }

  @Override
  public void setContextValue(String attribute, String value) {
    int offset = DDPROF.offsetOf(attribute);
    if (offset >= 0) {
      int encoding = DDPROF.encodeConstant(value);
      AgentSpan activeSpan = activeSpan();
      if (activeSpan.context() instanceof ProfilingContext) {
        ((ProfilingContext) activeSpan.context()).set(offset, encoding);
      }
      DDPROF.setContext(offset, encoding);
    }
  }

  @Override
  public void clearContextValue(String attribute) {
    int offset = DDPROF.offsetOf(attribute);
    if (offset >= 0) {
      AgentSpan activeSpan = activeSpan();
      if (activeSpan.context() instanceof ProfilingContext) {
        ((ProfilingContext) activeSpan.context()).set(offset, 0);
      }
      DDPROF.clearContext(offset);
    }
  }

  @Override
  public boolean isQueuingTimeEnabled() {
    return QUEUEING_TIME_ENABLED;
  }

  @Override
  public void recordQueueingTime(long duration) {}

  @Override
  public int[] createContextStorage(CharSequence operationName) {
    if (DDPROF.registeredTagCount() > 0) {
      int[] tags = new int[DDPROF.registeredTagCount()];
      updateOperationName(operationName, tags, false);
      return tags;
    }
    return EMPTY_TAGS;
  }

  @Override
  public void updateOperationName(CharSequence operationName, int[] storage, boolean active) {
    if (SPAN_NAME_ATTRIBUTED_ENABLED && operationName != null) {
      storage[0] = DDPROF.encodeConstant(operationName);
      if (active) {
        DDPROF.setContext(0, storage[0]);
      }
    }
  }

  @Override
  public void setContext(int offset, int value) {
    DDPROF.setContext(offset, value);
  }

  @Override
  public void clearContext(int offset) {
    DDPROF.clearContext(offset);
  }
}
