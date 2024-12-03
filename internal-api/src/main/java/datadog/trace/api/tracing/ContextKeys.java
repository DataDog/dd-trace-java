package datadog.trace.api.tracing;

import static datadog.context.ContextKey.named;

import datadog.context.ContextKey;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public interface ContextKeys {
  ContextKey<AgentSpan> SPAN_CONTEXT_KEY = named("span");
  ContextKey<AgentScope> SPAN_SCOPE_CONTEXT_KEY = named("span-scope");
}
