package datadog.trace.api.internal;

import datadog.trace.api.experimental.DataStreamsCheckpointer;
import datadog.trace.api.profiling.Profiling;

/**
 * Tracer internal features. Those features are not part of public API and can change or be removed
 * at any time.
 */
public interface InternalTracer {
  /**
   * Attach callbacks to the global scope manager.
   *
   * @param afterScopeActivatedCallback Callback on scope activation.
   * @param afterScopeClosedCallback Callback on scope close.
   */
  void addScopeListener(Runnable afterScopeActivatedCallback, Runnable afterScopeClosedCallback);

  void flush();

  void flushMetrics();

  Profiling getProfilingContext();

  TraceSegment getTraceSegment();

  /**
   * Return the global instance of the DataStreams checkpointer.
   *
   * @return DataStreamsCheckpointer instance.
   */
  DataStreamsCheckpointer getDataStreamsCheckpointer();
}
