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
  private static final ContextKey<String> PAGENT_SPAN_ID_KEY =
      ContextKey.named("llmobs_pagent_span_id");
  private static final ContextKey<String> PAGENT_NAME_KEY = ContextKey.named("llmobs_pagent_name");

  public static ContextScope attach(AgentSpanContext ctx) {
    return attach(ctx, null, null, null);
  }

  /**
   * Attach an LLMObs span context, optionally propagating a session_id to descendant LLMObs spans.
   * When sessionId is non-null and non-empty, child LLMObs spans started under this context that do
   * not specify their own sessionId will inherit it via {@link #currentSessionId()}.
   */
  public static ContextScope attach(AgentSpanContext ctx, String sessionId) {
    return attach(ctx, sessionId, null, null);
  }

  /**
   * Attach an LLMObs span context, propagating session_id and agent attribution to descendant
   * LLMObs spans. pagentSpanId identifies the nearest agent-kind ancestor; pagentName is its name
   * (may be null if it failed wire-safety validation).
   */
  public static ContextScope attach(
      AgentSpanContext ctx, String sessionId, String pagentSpanId, String pagentName) {
    Context updated = Context.current().with(CONTEXT_KEY, ctx);
    if (sessionId != null && !sessionId.isEmpty()) {
      updated = updated.with(SESSION_ID_KEY, sessionId);
    }
    if (pagentSpanId != null && !pagentSpanId.isEmpty()) {
      updated = updated.with(PAGENT_SPAN_ID_KEY, pagentSpanId);
      if (pagentName != null) {
        updated = updated.with(PAGENT_NAME_KEY, pagentName);
      }
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

  /**
   * Return the parent agent span ID propagated from an enclosing agent-kind LLMObs span, or null.
   */
  public static String currentParentAgentSpanId() {
    return Context.current().get(PAGENT_SPAN_ID_KEY);
  }

  /**
   * Return the parent agent name propagated from an enclosing agent-kind LLMObs span, or null.
   */
  public static String currentParentAgentName() {
    return Context.current().get(PAGENT_NAME_KEY);
  }
}
