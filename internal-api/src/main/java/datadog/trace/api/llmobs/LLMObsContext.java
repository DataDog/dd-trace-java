package datadog.trace.api.llmobs;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;

public final class LLMObsContext {
  public static final String ROOT_SPAN_ID = "undefined";

  private LLMObsContext() {
    // ~
  }

  private static final ContextKey<AgentSpanContext> CONTEXT_KEY = ContextKey.named("llmobs_span");
  private static final ContextKey<String> SESSION_ID_KEY = ContextKey.named("llmobs_session_id");

  public static ContextScope attach(AgentSpanContext ctx) {
    return attach(ctx, null);
  }

  /**
   * Attach an LLMObs span context, optionally propagating a session_id to descendant LLMObs spans.
   * When sessionId is non-null and non-empty, child LLMObs spans started under this context that do
   * not specify their own sessionId will inherit it via {@link #currentSessionId()}.
   */
  public static ContextScope attach(AgentSpanContext ctx, String sessionId) {
    Context updated = Context.current().with(CONTEXT_KEY, ctx);
    if (sessionId != null && !sessionId.isEmpty()) {
      updated = updated.with(SESSION_ID_KEY, sessionId);
    }
    return updated.attach();
  }

  public static AgentSpanContext current() {
    return Context.current().get(CONTEXT_KEY);
  }

  /**
   * Return the session_id propagated from an enclosing LLMObs span, or null if no parent set one.
   */
  public static String currentSessionId() {
    return Context.current().get(SESSION_ID_KEY);
  }
}
