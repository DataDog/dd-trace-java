package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public interface EndpointCheckpointer {
  /**
   * Callback to be called when a root span is finished (together with the trace). With partial
   * flushes, this may be called multiple times when any of the root span's children are finished
   * even if the root span is not.
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
