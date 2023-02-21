package com.datadog.profiling.ddprof;

import datadog.trace.api.Dictionary;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * This class must be installed early to be able to see all scope initialisations, which means it
 * must not be modified to depend on JFR, so that it can be installed before tracing starts.
 */
public class DatadogProfilingIntegration implements ProfilingContextIntegration {

  private static final DatadogProfiler DDPROF = DatadogProfiler.getInstance();
  private static final boolean WALLCLOCK_ENABLED =
      DatadogProfilerConfig.isWallClockProfilerEnabled();

  private static final boolean QUEUEING_TIME_ENABLED =
      WALLCLOCK_ENABLED && DatadogProfilerConfig.isQueueingTimeEnabled();

  private static final AtomicReferenceFieldUpdater<DatadogProfilingIntegration, Dictionary>
      CONSTANT_POOL_UPDATER =
          AtomicReferenceFieldUpdater.newUpdater(
              DatadogProfilingIntegration.class, Dictionary.class, "constantPool");

  private volatile Dictionary constantPool;

  private static final int WALLCLOCK_INTERVAL = DatadogProfilerConfig.getWallInterval();

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
}
