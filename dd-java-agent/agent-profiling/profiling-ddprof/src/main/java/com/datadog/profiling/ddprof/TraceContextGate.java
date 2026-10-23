package com.datadog.profiling.ddprof;

import datadog.trace.api.config.ProfilingConfig;
import datadog.trace.api.profiling.ProfilingEnablement;
import datadog.trace.bootstrap.config.provider.ConfigProvider;

/**
 * Decides whether spans write their trace context into the Datadog profiler's thread context. When
 * profiling is only armed, nothing is written until a recording is started, and writing stops again
 * once it is stopped. Otherwise the context is always written.
 */
final class TraceContextGate {
  // don't use Config because it may use ThreadPoolExecutor to initialize itself
  static final TraceContextGate INSTANCE =
      new TraceContextGate(
          ProfilingEnablement.of(
                  ConfigProvider.getInstance().getString(ProfilingConfig.PROFILING_ENABLED))
              == ProfilingEnablement.ARMED);

  private final boolean armed;
  private volatile boolean recording;

  TraceContextGate(boolean armed) {
    this.armed = armed;
  }

  boolean isOpen() {
    // only pay for the volatile read when armed
    return !armed || recording;
  }

  void onRecordingStarted() {
    recording = true;
  }

  void onRecordingStopped() {
    recording = false;
  }
}
