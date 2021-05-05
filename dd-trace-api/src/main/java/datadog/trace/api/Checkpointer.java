package datadog.trace.api;

public interface Checkpointer {

  /** Modifier tag to make an event an end event.
   * The LSB is chosen to allow for good varint compression. */
  int END = 0x1;
  /** Marks the start of a span */
  int SPAN = 0x2;
  /**
   * Indicates that the instrumentation expects synchronous CPU bound work to take place. The CPU
   * work is considered to end when the next event for the same span without this flag set is
   * received.
   */
  int CPU = 0x4;
  /**
   * Indicates that the instrumentation expects the span to make a thread migration and resume on
   * another thread. This does not mean that the work on the current thread will cease, unless it is
   * the last event for the span on the thread.
   */
  int THREAD_MIGRATION = 0x8;

  /**
   * Notifies the profiler that the span has reached a certain state
   *
   * @param traceId the traceId of the trace the span belongs to.
   * @param spanId the span's identifier
   * @param flags a description of the event
   */
  void checkpoint(DDId traceId, DDId spanId, int flags);
}
