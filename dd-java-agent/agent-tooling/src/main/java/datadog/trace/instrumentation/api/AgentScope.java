package datadog.trace.instrumentation.api;

import datadog.trace.context.TraceScope;

public interface AgentScope extends TraceScope {
  AgentSpan span();

  @Override
  void close();
}
