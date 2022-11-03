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
   * @param published {@literal true} the trace and root span published
   * @param checkpointsSampled {@literal true} the checkpoints were sampled
   */
  void onRootSpanWritten(AgentSpan rootSpan, boolean published, boolean checkpointsSampled);

  /**
   * Callback to be called when a root span is started
   *
   * @param rootSpan the local root span of the trace
   */
  void onRootSpanStarted(AgentSpan rootSpan);
}
