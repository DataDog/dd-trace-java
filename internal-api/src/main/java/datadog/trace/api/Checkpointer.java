package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public interface Checkpointer extends EndpointCheckpointer {

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
}
