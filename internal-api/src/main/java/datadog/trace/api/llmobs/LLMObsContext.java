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
  private static final ContextKey<String> AGENT_VERSION_KEY =
      ContextKey.named("llmobs_agent_version");
  private static final ContextKey<String> PAGENT_SPAN_ID_KEY =
      ContextKey.named("llmobs_pagent_span_id");
  private static final ContextKey<String> PAGENT_NAME_KEY = ContextKey.named("llmobs_pagent_name");

  /**
   * Attach an LLMObs span context, leaving any inherited session_id/agent_version from an enclosing
   * scope untouched (see {@link #currentSessionId()}, {@link #currentAgentVersion()}).
   */
  public static ContextScope attach(AgentSpanContext ctx) {
    return Context.current().with(CONTEXT_KEY, ctx).attach();
  }

  /**
   * Attach an LLMObs span context, optionally propagating a session_id to descendant LLMObs spans.
   * When sessionId is non-null and non-empty, child LLMObs spans started under this context that do
   * not specify their own sessionId will inherit it via {@link #currentSessionId()}. A null or
   * empty sessionId clears any session_id inherited from an enclosing scope.
   */
  public static ContextScope attach(AgentSpanContext ctx, String sessionId) {
    return Context.current()
        .with(CONTEXT_KEY, ctx)
        .with(SESSION_ID_KEY, sessionId != null && !sessionId.isEmpty() ? sessionId : null)
        .attach();
  }

  /**
   * Attach an LLMObs span context, optionally propagating a session_id and an agent_version to
   * descendant LLMObs spans. See {@link #attach(AgentSpanContext, String)}. Same
   * clears-if-null-or-empty semantics apply to agentVersion via {@link #currentAgentVersion()} —
   * callers (e.g. {@code DDLLMObsSpan}) are expected to pass the already-resolved effective value,
   * so that a stale value from an unrelated context is always cleared rather than silently carried
   * forward.
   */
  public static ContextScope attach(AgentSpanContext ctx, String sessionId, String agentVersion) {
    return Context.current()
        .with(CONTEXT_KEY, ctx)
        .with(SESSION_ID_KEY, sessionId != null && !sessionId.isEmpty() ? sessionId : null)
        .with(
            AGENT_VERSION_KEY,
            agentVersion != null && !agentVersion.isEmpty() ? agentVersion : null)
        .attach();
  }

  /**
   * Attach an LLMObs span context, propagating session_id, agent_version, and agent attribution to
   * descendant LLMObs spans. pagentSpanId identifies the nearest agent-kind ancestor; pagentName is
   * its name (may be null). Both pagent keys are always written — null clears stale values from an
   * outer scope.
   */
  public static ContextScope attach(
      AgentSpanContext ctx,
      String sessionId,
      String agentVersion,
      String parentAgentSpanId,
      String parentAgentName) {
    Context updated = Context.current().with(CONTEXT_KEY, ctx);
    if (sessionId != null && !sessionId.isEmpty()) {
      updated = updated.with(SESSION_ID_KEY, sessionId);
    }
    updated =
        updated.with(
            AGENT_VERSION_KEY,
            agentVersion != null && !agentVersion.isEmpty() ? agentVersion : null);
    if (parentAgentSpanId != null && !parentAgentSpanId.isEmpty()) {
      updated = updated.with(PAGENT_SPAN_ID_KEY, parentAgentSpanId);
      // Always update the name key even when parentAgentName is null. Per the Context API
      // contract (Context.java: "Mapping to a null value will remove the key-value from the
      // context copy"), with(key, null) clears any name set by an outer agent scope, so a
      // descendant of an unsafe-named inner agent never inherits the outer agent's name.
      updated = updated.with(PAGENT_NAME_KEY, parentAgentName);
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
   * Return the agent_version propagated from an enclosing agent span, or null if no ancestor set
   * one.
   */
  public static String currentAgentVersion() {
    return Context.current().get(AGENT_VERSION_KEY);
  }

  /**
   * Return the parent agent span ID propagated from an enclosing agent-kind LLMObs span, or null.
   */
  public static String currentParentAgentSpanId() {
    return Context.current().get(PAGENT_SPAN_ID_KEY);
  }

  /** Return the parent agent name propagated from an enclosing agent-kind LLMObs span, or null. */
  public static String currentParentAgentName() {
    return Context.current().get(PAGENT_NAME_KEY);
  }
}
