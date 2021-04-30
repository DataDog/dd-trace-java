package datadog.trace.api;

public interface Checkpointer {

  /** Modifier tag to make an event an end event */
  int END = 0x80000000;
  /** Marks the start of a span */
  int SPAN = 0x1;
  /**
   * Indicates that the instrumentation expects synchronous CPU bound work to take place. The CPU
   * work is considered to end when the next event for the same span without this flag set is
   * received.
   */
  int CPU = 0x2;
  /**
   * Indicates that the instrumentation expects IO to take place. The IO is considered to end when
   * the next event for the same span without this flag set is received.
   */
  int IO = 0x4;
  /**
   * Indicates that the instrumentation expects the work associated with the span to be enqueued and
   * become idle. The idle time is considered to end when the next event for the same span without
   * this flag set is received.
   */
  int ENQUEUED = 0x8;
  /**
   * Indicates that the instrumentation expects the span to make a thread migration and resume on
   * another thread. This does not mean that the work on the current thread will cease, unless it is
   * the last event for the span on the thread.
   */
  int THREAD_MIGRATION = 0x10;

  /**
   * Notifies the profiler that the span has reached a certain state
   *
   * @param traceId the traceId of the trace the span belongs to.
   * @param spanId the span's identifier
   * @param flags a description of the event
   */
  void checkpoint(DDId traceId, DDId spanId, int flags);
}
