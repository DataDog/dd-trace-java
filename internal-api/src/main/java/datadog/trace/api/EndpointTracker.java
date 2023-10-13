package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

/** A simple listener-like interface for tracking the end-point writes */
public interface EndpointTracker {

  EndpointTracker NO_OP = span -> {};

  /**
   * Called when a local root span (associated with an end-point) is written
   *
   * @param span the local root span
   */
  void endpointWritten(AgentSpan span);
}
