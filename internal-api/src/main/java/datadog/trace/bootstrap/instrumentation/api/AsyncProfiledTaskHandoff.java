/*
 * Copyright 2026, Datadog, Inc.
 */
package datadog.trace.bootstrap.instrumentation.api;

/**
 * Synchronises activation timing between thread-pool {@code beforeExecute} queue-timer
 * reporting and {@link ProfilerContext} activation in {@code Wrapper#run} so the synthetic
 * work-segment span id in {@code datadog.QueueTime} and the {@code SpanNode} emitted in
 * {@code onTaskDeactivation} are identical.
 */
public final class AsyncProfiledTaskHandoff {

  private static final ThreadLocal<Long> PENDING_START_NANO = new ThreadLocal<>();

  private AsyncProfiledTaskHandoff() {}

  /**
   * Set by the consumer thread in {@code QueueTimeTracker#report} when a queue self-loop is
   * disambiguated with a synthetic id; read once at the start of {@code Wrapper#run}.
   */
  public static void setPendingActivationStartNano(long startNano) {
    PENDING_START_NANO.set(startNano);
  }

  /** @return the pending value if present; clears the thread-local */
  public static Long takePendingActivationStartNano() {
    Long v = PENDING_START_NANO.get();
    PENDING_START_NANO.remove();
    return v;
  }
}
