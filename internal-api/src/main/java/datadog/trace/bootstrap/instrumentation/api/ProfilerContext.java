package datadog.trace.bootstrap.instrumentation.api;

public interface ProfilerContext {

  long getSpanId();

  /**
   * @return the span id of the local root span, or the span itself
   */
  long getRootSpanId();

  /**
   * @return the span id of the parent span, or 0 if this is the root
   */
  long getParentSpanId();

  int getEncodedOperationName();

  CharSequence getOperationName();

  int getEncodedResourceName();

  CharSequence getResourceName();

  /** Java thread ID of the thread that finished this span (captured at span finish time). */
  default long getExecutionThreadId() {
    return 0;
  }

  /** Name of the thread that finished this span (captured at span finish time). */
  default String getExecutionThreadName() {
    return "";
  }

  /**
   * Records the execution thread for this span. First-write-wins: once set by a worker thread (via
   * {@code onTaskActivation}), subsequent calls from e.g. a Netty event loop callback are ignored.
   */
  default void captureExecutionThread(long threadId, String threadName) {}

  /**
   * Synthetic span id for the per-activation work segment of this context on the current thread.
   * Must match {@code DatadogProfilingIntegration#onTaskDeactivation} so that {@code TaskBlock} /
   * {@code datadog.QueueTime} edges and {@code SpanNode} for the work window share the same id.
   *
   * @param activationStartNano {@link System#nanoTime()} at task activation
   * @return xor mix of base span, thread, and start time; never 0
   */
  default long getSyntheticWorkSpanIdForActivation(long activationStartNano) {
    return getSpanId() ^ (Thread.currentThread().getId() << 32) ^ activationStartNano;
  }
}
