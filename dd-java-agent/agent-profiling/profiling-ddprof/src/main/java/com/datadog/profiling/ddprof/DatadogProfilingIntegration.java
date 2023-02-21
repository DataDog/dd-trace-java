package com.datadog.profiling.ddprof;

import datadog.trace.api.Dictionary;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

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

  private static final AtomicReferenceFieldUpdater<DatadogProfilingIntegration, Dictionary>
      CONSTANT_POOL_UPDATER =
          AtomicReferenceFieldUpdater.newUpdater(
              DatadogProfilingIntegration.class, Dictionary.class, "constantPool");

  private volatile Dictionary constantPool;

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

  @Override
  public void setConstantPool(Dictionary dictionary) {
    CONSTANT_POOL_UPDATER.compareAndSet(this, null, dictionary);
  }

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
      storage[0] = constantPool.encode(operationName);
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
