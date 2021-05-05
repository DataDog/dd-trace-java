package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public interface SpanCheckpointer {
  void onComplexEvent(AgentSpan span, int flags);

  void onStart(AgentSpan span);

  void onCommenceWork(AgentSpan span);

  void onCompleteWork(AgentSpan span);

  void onThreadMigration(AgentSpan span);

  void onAsyncResume(AgentSpan span);

  void onFinish(AgentSpan span);
}
