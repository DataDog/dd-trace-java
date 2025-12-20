package datadog.trace.api.llmobs;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.api.DDSpanId;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;

public final class LLMObsContext {
  public static final String ROOT_SPAN_ID = "undefined";

  private LLMObsContext() {
    // ~
  }

  private static final ContextKey<AgentSpanContext> CONTEXT_KEY = ContextKey.named("llmobs_span");

  public static ContextScope attach(AgentSpanContext ctx) {
    return Context.current().with(CONTEXT_KEY, ctx).attach();
  }

  public static AgentSpanContext current() {
    return Context.current().get(CONTEXT_KEY);
  }

  public static String parentSpanId() {
    AgentSpanContext parentLlmContext = current();
    if (parentLlmContext == null) {
      return ROOT_SPAN_ID;
    }
    long parentLlmSpanId = parentLlmContext.getSpanId();
    if (parentLlmSpanId == DDSpanId.ZERO) {
      return ROOT_SPAN_ID;
    }
    return Long.toString(parentLlmSpanId);
  }
}
