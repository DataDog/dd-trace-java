package datadog.trace.api.llmobs;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;

public final class LLMObsContext {
  public static final String ROOT_SPAN_ID = "undefined";

  /** Sampling decision value meaning "retain this span". Matches dd-trace-py and dd-trace-js. */
  public static final String SAMPLING_DECISION_SAMPLED = "1";

  /**
   * Sampling decision value meaning "drop this span". The LLM Observability intake drops spans
   * carrying this value; any other value, including an absent one, is retained.
   */
  public static final String SAMPLING_DECISION_DROPPED = "0";

  private LLMObsContext() {
    // ~
  }

  private static final ContextKey<AgentSpanContext> CONTEXT_KEY = ContextKey.named("llmobs_span");
  private static final ContextKey<String> SESSION_ID_KEY = ContextKey.named("llmobs_session_id");
  private static final ContextKey<String> SAMPLE_RATE_KEY = ContextKey.named("llmobs_sample_rate");
  private static final ContextKey<String> SAMPLING_DECISION_KEY =
      ContextKey.named("llmobs_sampling_decision");

  public static ContextScope attach(AgentSpanContext ctx) {
    return attach(ctx, null);
  }

  public static ContextScope attach(AgentSpanContext ctx, String sessionId) {
    return attach(ctx, sessionId, null, null);
  }

  /**
   * Attach an LLMObs span context, optionally propagating a session_id and a sampling decision to
   * descendant LLMObs spans. When sessionId is non-null and non-empty, child LLMObs spans started
   * under this context that do not specify their own sessionId will inherit it via {@link
   * #currentSessionId()}.
   *
   * <p>The sampling decision is computed once at the root of an LLMObs trace and inherited
   * unchanged by every descendant, so that a trace is retained or dropped as a whole. Both sampling
   * values are carried pre-formatted so that every span in the trace reports byte-identical values.
   */
  public static ContextScope attach(
      AgentSpanContext ctx, String sessionId, String sampleRate, String samplingDecision) {
    Context updated = Context.current().with(CONTEXT_KEY, ctx);
    if (sessionId != null && !sessionId.isEmpty()) {
      updated = updated.with(SESSION_ID_KEY, sessionId);
    }
    if (samplingDecision != null) {
      updated =
          updated.with(SAMPLING_DECISION_KEY, samplingDecision).with(SAMPLE_RATE_KEY, sampleRate);
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
}
