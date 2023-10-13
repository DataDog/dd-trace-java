package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public interface EndpointCheckpointer {
  /**
   * Callback to be called when a root span is written (together with the trace)
   *
   * @param rootSpan the local root span of the trace
   * @param tracker the endpoint tracker
   */
  void onRootSpanFinished(AgentSpan rootSpan, EndpointTracker tracker);

  /**
   * Callback to be called when a root span is started
   *
   * @param rootSpan the local root span of the trace
   * @return an endpoint tracker
   */
  EndpointTracker onRootSpanStarted(AgentSpan rootSpan);
}
