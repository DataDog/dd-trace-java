package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public interface Checkpointer {

  /**
   * Modifier tag to make an event an end event. The LSB is chosen to allow for good varint
   * compression.
   */
  int END = 0x1;
  /**
   * Indicates that the instrumentation expects synchronous CPU bound work to take place. The CPU
   * work is considered to end when the next event for the same span without this flag set is
   * received.
   */
  int CPU = 0x2;
  /** Marks the start of a span */
  int SPAN = 0x4 | CPU;
  /**
   * Indicates that the instrumentation expects the span to make a thread migration and resume on
   * another thread. This does not mean that the work on the current thread will cease, unless it is
   * the last event for the span on the thread.
   */
  int THREAD_MIGRATION = 0x8 | CPU;

  /**
   * Notifies the profiler that the span has reached a certain state
   *
   * @param span the span
   * @param flags a description of the event
   */
  void checkpoint(AgentSpan span, int flags);

  /**
   * Callback to be called when a root span is written (together with the trace)
   *
   * @param rootSpan the local root span of the trace
   * @param published {@literal true} the trace and root span sampled and published
   */
  void onRootSpan(AgentSpan rootSpan, boolean published);
}
