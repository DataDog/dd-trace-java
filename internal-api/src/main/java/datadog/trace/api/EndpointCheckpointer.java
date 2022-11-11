package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public interface EndpointCheckpointer {
  /**
   * Callback to be called when a root span is written (together with the trace)
   *
   * @param rootSpan the local root span of the trace
   * @param published {@literal true} the trace and root span published
   */
  void onRootSpanFinished(AgentSpan rootSpan, boolean published);

  /**
   * Callback to be called when a root span is started
   *
   * @param rootSpan the local root span of the trace
   */
  void onRootSpanStarted(AgentSpan rootSpan);
}
