package datadog.trace.bootstrap.instrumentation.api;

import datadog.context.ContextKey;

final class InternalContextKeys {
  static final ContextKey<AgentSpan> SPAN_KEY = ContextKey.named("dd-span-key");

  private InternalContextKeys() {}
}
