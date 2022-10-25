package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public interface SpanCheckpointer {
  void checkpoint(AgentSpan span, int flags);

  void onStartWork(AgentSpan span);

  void onFinishWork(AgentSpan span);

  void onRootSpanStarted(AgentSpan root);

  void onRootSpanFinished(AgentSpan root, boolean published);
}
