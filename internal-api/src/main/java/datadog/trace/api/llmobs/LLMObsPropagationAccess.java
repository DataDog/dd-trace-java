package datadog.trace.api.llmobs;

/**
 * Bridge interface allowing the LLMObs span (in agent-llmobs) to read and write agent attribution
 * propagation tags on the underlying APM span context (in dd-trace-core) without a direct module
 * dependency. Implemented by DDSpanContext.
 */
public interface LLMObsPropagationAccess {

  /** Returns the propagated parent agent span ID, or null if not set. */
  String getParentAgentSpanId();

  /** Returns the propagated parent agent name, or null if not set. */
  String getParentAgentName();

  /** Sets the parent agent span ID to propagate on outgoing requests. */
  void setParentAgentSpanId(String value);

  /** Sets the parent agent name to propagate on outgoing requests. Null clears it. */
  void setParentAgentName(String value);
}
