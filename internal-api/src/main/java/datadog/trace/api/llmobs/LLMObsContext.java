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

  public static ContextScope attach(AgentSpanContext ctx) {
    return attach(ctx, null);
  }

  /**
   * Attach an LLMObs span context, optionally propagating a session_id to descendant LLMObs spans.
   * When sessionId is non-null and non-empty, child LLMObs spans started under this context that do
   * not specify their own sessionId will inherit it via {@link #currentSessionId()}.
   */
  public static ContextScope attach(AgentSpanContext ctx, String sessionId) {
    return attach(ctx, sessionId, null);
  }

  /**
   * Attach an LLMObs span context, optionally propagating a session_id and an agent_version to
   * descendant LLMObs spans. See {@link #attach(AgentSpanContext, String)}. Same inherit-if-absent,
   * explicit-value-wins semantics apply to agentVersion via {@link #currentAgentVersion()}.
   */
  public static ContextScope attach(AgentSpanContext ctx, String sessionId, String agentVersion) {
    Context updated = Context.current().with(CONTEXT_KEY, ctx);
    if (sessionId != null && !sessionId.isEmpty()) {
      updated = updated.with(SESSION_ID_KEY, sessionId);
    }
    if (agentVersion != null && !agentVersion.isEmpty()) {
      updated = updated.with(AGENT_VERSION_KEY, agentVersion);
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
}
