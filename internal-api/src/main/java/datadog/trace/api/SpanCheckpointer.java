package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public interface SpanCheckpointer extends EndpointCheckpointer {
  void checkpoint(AgentSpan span, int flags);

  void onStartWork(AgentSpan span);

  void onFinishWork(AgentSpan span);
}
