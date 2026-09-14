package datadog.trace.api.llmobs;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;

public final class LLMObsContext {
  public static final String ROOT_SPAN_ID = "undefined";

  /** Sampling decision value meaning "retain this span". */
  public static final String SAMPLING_DECISION_SAMPLED = "1";

  /** Sampling decision value meaning "drop this span". */
  public static final String SAMPLING_DECISION_DROPPED = "0";

  private LLMObsContext() {
    // ~
  }

  private static final ContextKey<AgentSpanContext> CONTEXT_KEY = ContextKey.named("llmobs_span");
  private static final ContextKey<String> SESSION_ID_KEY = ContextKey.named("llmobs_session_id");
  private static final ContextKey<String> AGENT_VERSION_KEY =
      ContextKey.named("llmobs_agent_version");
  private static final ContextKey<String> SAMPLE_RATE_KEY = ContextKey.named("llmobs_sample_rate");
  private static final ContextKey<String> SAMPLING_DECISION_KEY =
      ContextKey.named("llmobs_sampling_decision");
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
        .with(SESSION_ID_KEY, emptyToNull(sessionId))
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
        .with(SESSION_ID_KEY, emptyToNull(sessionId))
        .with(AGENT_VERSION_KEY, emptyToNull(agentVersion))
        .attach();
  }

  /**
   * Attach an LLMObs span context, propagating a session_id, an agent_version, a sampling decision,
   * and agent attribution to descendant LLMObs spans. See {@link #attach(AgentSpanContext, String,
   * String)} — the same clears-if-null-or-empty semantics apply to every value, so callers are
   * expected to pass already-resolved effective values.
   *
   * <p>This overload carries every propagated value at once because a span's scope is attached
   * exactly once: three independent mechanisms (session, sampling, attribution) share one context,
   * so they cannot be attached by separate calls without nesting redundant scopes.
   *
   * <p>The sampling decision is computed once at the root of an LLMObs trace and inherited
   * unchanged by every descendant, so that a trace is retained or dropped as a whole. Both sampling
   * values are carried pre-formatted so that every span in the trace reports byte-identical values.
   * The rate travels only alongside a decision — a rate on its own says nothing about whether the
   * trace was kept — so a null decision clears both.
   *
   * <p>parentAgentSpanId identifies the nearest agent-kind ancestor; parentAgentName is its name
   * (may be null). Both pagent keys are always written. Per the Context API contract (Context.java:
   * "Mapping to a null value will remove the key-value from the context copy"), with(key, null)
   * clears any stale value inherited from an outer scope. This prevents two leakage scenarios:
   *
   * <ol>
   *   <li>An unsafe-named inner agent must not let descendants see the outer agent's name.
   *   <li>A non-agent span whose trace-ID gate blocked attribution must not let its same-trace
   *       children pick up a pagent ID that belongs to a different trace.
   * </ol>
   *
   * <p><strong>In-process only.</strong> This context is not serialized into distributed trace
   * headers, so each service in a distributed trace decides independently. Because the decision is
   * a pure function of the APM trace ID and the configured rate, services configured at the same
   * rate agree; services configured at different rates disagree and the trace is retained in part.
   * A decision propagated by an upstream dd-trace-py or dd-trace-js service is likewise not read
   * here. Closing that gap needs propagated trace tags mirroring the existing {@code _dd.p.ksr}.
   */
  public static ContextScope attach(
      AgentSpanContext ctx,
      String sessionId,
      String agentVersion,
      String sampleRate,
      String samplingDecision,
      String parentAgentSpanId,
      String parentAgentName) {
    String decision = emptyToNull(samplingDecision);
    return Context.current()
        .with(CONTEXT_KEY, ctx)
        .with(SESSION_ID_KEY, emptyToNull(sessionId))
        .with(AGENT_VERSION_KEY, emptyToNull(agentVersion))
        .with(SAMPLING_DECISION_KEY, decision)
        .with(SAMPLE_RATE_KEY, decision == null ? null : emptyToNull(sampleRate))
        .with(PAGENT_SPAN_ID_KEY, parentAgentSpanId)
        .with(PAGENT_NAME_KEY, parentAgentName)
        .attach();
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
   * Return the sample rate that produced {@link #currentSamplingDecision()}, or null if no
   * enclosing LLMObs span made a sampling decision.
   */
  public static String currentSampleRate() {
    return Context.current().get(SAMPLE_RATE_KEY);
  }

  /**
   * Return the sampling decision propagated from an enclosing LLMObs span, or null if none was
   * made. A null value identifies the current span as the root of an LLMObs trace, which is the
   * only place a decision is computed.
   */
  public static String currentSamplingDecision() {
    return Context.current().get(SAMPLING_DECISION_KEY);
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

  private static String emptyToNull(String value) {
    return value != null && !value.isEmpty() ? value : null;
  }
}
