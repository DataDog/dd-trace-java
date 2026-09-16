package com.datadog.profiling.ddprof;

import datadog.trace.api.profiling.ProfilingContextAttribute;
import datadog.trace.api.profiling.ProfilingScope;

public class DatadogProfilingScope implements ProfilingScope {
  private final DatadogProfiler profiler;
  // Snapshot of the app-managed context slots at scope creation (those registered via
  // ProfilingContextAttribute). Restored on close() so ambient values set before this
  // scope are not lost when the scope exits.
  // Span context slots (managed by DatadogProfilingIntegration) are not snapshotted
  // here; their lifecycle is controlled by the tracer's activate/deactivate path.
  private final DatadogProfiler.AppContextSnapshot savedAppContext;

  public DatadogProfilingScope(DatadogProfiler profiler) {
    this.profiler = profiler;
    this.savedAppContext = profiler.saveAppContext();
  }

  @Override
  public void setContextValue(String attribute, String value) {
    profiler.setContextValue(attribute, value);
  }

  @Override
  public void setContextValue(ProfilingContextAttribute attribute, String value) {
    if (attribute instanceof DatadogProfilerContextSetter) {
      ((DatadogProfilerContextSetter) attribute).set(value);
    }
  }

  @Override
  public void clearContextValue(String attribute) {
    profiler.clearContextValue(attribute);
  }

  @Override
  public void clearContextValue(ProfilingContextAttribute attribute) {
    if (attribute instanceof DatadogProfilerContextSetter) {
      ((DatadogProfilerContextSetter) attribute).clear();
    }
  }

  @Override
  public void close() {
    // Restores the app-managed context slots that were active when this scope opened.
    // Span context slots are NOT touched here; they are managed independently by the
    // tracer via DatadogProfilingIntegration.activate()/close().
    profiler.restoreAppContext(savedAppContext);
    profiler.syncNativeAppContext();
  }
}
