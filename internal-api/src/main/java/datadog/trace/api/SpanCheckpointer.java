package datadog.trace.api;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public interface SpanCheckpointer {
  void checkpoint(AgentSpan span, int flags);

  void onStart(AgentSpan span);

  void onStartWork(AgentSpan span);

  void onFinishWork(AgentSpan span);

  void onStartThreadMigration(AgentSpan span);

  void onFinishThreadMigration(AgentSpan span);

  void onFinish(AgentSpan span);

  void onRootSpan(AgentSpan root, boolean published);
}
