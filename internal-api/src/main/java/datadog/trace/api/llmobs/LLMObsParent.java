package datadog.trace.api.llmobs;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import javax.annotation.Nullable;

/**
 * The LLM Observability parent a span inherits from, resolved once when the span starts. Shared by
 * manual spans ({@code DDLLMObsSpan}) and auto-instrumented ones, so that every LLMObs span picks
 * its parent the same way.
 *
 * <p>There are two candidates, tried in order:
 *
 * <ol>
 *   <li>The active in-process {@link LLMObsContext}, but only when it belongs to the same trace as
 *       the span. A context from another trace (leaked across an async boundary, or left open by a
 *       previously handled message) contributes nothing.
 *   <li>The {@code _dd.p.llmobs_*} values that arrived on the inbound request, from {@link
 *       AgentSpanContext#getExtractedLLMObsValues()}.
 * </ol>
 *
 * <p>If neither applies the span is the root of its own LLMObs trace, which {@link #isRoot()}
 * reports.
 */
public final class LLMObsParent {

  /** Where the inherited values came from. */
  public enum Source {
    /** The active in-process LLMObs context on the same trace. */
    IN_PROCESS,
    /** The LLMObs context that arrived on the inbound request. */
    PROPAGATED,
    /** Neither: the span is the root of its own LLMObs trace. */
    NONE
  }

  private static final LLMObsParent ROOT =
      new LLMObsParent(
          Source.NONE,
          LLMObsContext.ROOT_SPAN_ID,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);

  public final Source source;

  /** The parent's span id, or {@link LLMObsContext#ROOT_SPAN_ID} when there is none. */
  public final String parentId;

  /**
   * The LLMObs trace id in its stored form (hex above 2^64, decimal below), or {@code null} when
   * there is none to inherit and the caller seeds it from the APM trace id.
   */
  @Nullable public final String traceId;

  @Nullable public final String mlApp;
  @Nullable public final String sessionId;

  /** Only ever inherited in-process; it is not propagated across services. */
  @Nullable public final String agentVersion;

  @Nullable public final String parentAgentSpanId;
  @Nullable public final String parentAgentName;
  @Nullable public final String sampleRate;
  @Nullable public final String samplingDecision;

  /**
   * The in-process context that was active but ignored because it belongs to another trace, or
   * {@code null} if there was none. Exposed so a caller can report it.
   */
  @Nullable public final AgentSpanContext ignoredContext;

  private LLMObsParent(
      Source source,
      String parentId,
      @Nullable String traceId,
      @Nullable String mlApp,
      @Nullable String sessionId,
      @Nullable String agentVersion,
      @Nullable String parentAgentSpanId,
      @Nullable String parentAgentName,
      @Nullable String sampleRate,
      @Nullable String samplingDecision,
      @Nullable AgentSpanContext ignoredContext) {
    this.source = source;
    this.parentId = parentId;
    this.traceId = traceId;
    this.mlApp = mlApp;
    this.sessionId = sessionId;
    this.agentVersion = agentVersion;
    this.parentAgentSpanId = parentAgentSpanId;
    this.parentAgentName = parentAgentName;
    this.sampleRate = sampleRate;
    this.samplingDecision = samplingDecision;
    this.ignoredContext = ignoredContext;
  }

  /** Resolves the LLMObs parent of a span that has just started. */
  public static LLMObsParent resolve(AgentSpan span) {
    AgentSpanContext active = LLMObsContext.current();
    AgentSpanContext ignored = null;
    if (active != null) {
      if (active.getTraceId().equals(span.getTraceId())) {
        return new LLMObsParent(
            Source.IN_PROCESS,
            String.valueOf(active.getSpanId()),
            emptyToNull(LLMObsContext.currentTraceId()),
            emptyToNull(LLMObsContext.currentMlApp()),
            emptyToNull(LLMObsContext.currentSessionId()),
            emptyToNull(LLMObsContext.currentAgentVersion()),
            emptyToNull(LLMObsContext.currentParentAgentSpanId()),
            emptyToNull(LLMObsContext.currentParentAgentName()),
            emptyToNull(LLMObsContext.currentSampleRate()),
            emptyToNull(LLMObsContext.currentSamplingDecision()),
            null);
      }
      ignored = active;
    }

    LLMObsPropagationValues propagated = span.spanContext().getExtractedLLMObsValues();
    if (propagated != null) {
      String parentAgentSpanId = emptyToNull(propagated.parentAgentSpanId);
      // Adopted together or not at all: a rate means nothing without the decision it produced, and
      // a lone decision would be read against this service's rate rather than the one behind it.
      boolean hasVerdict =
          emptyToNull(propagated.sampleRate) != null
              && emptyToNull(propagated.samplingDecision) != null;
      return new LLMObsParent(
          Source.PROPAGATED,
          propagated.parentId != null && !propagated.parentId.isEmpty()
              ? propagated.parentId
              : LLMObsContext.ROOT_SPAN_ID,
          // Adopt the caller's LLMObs trace id so the trace stays whole across the boundary. It
          // arrives as decimal and is stored as hex.
          LLMObsTraceId.fromWire(propagated.traceId),
          emptyToNull(propagated.mlApp),
          emptyToNull(propagated.sessionId),
          null,
          parentAgentSpanId,
          parentAgentSpanId != null ? emptyToNull(propagated.parentAgentName) : null,
          hasVerdict ? propagated.sampleRate : null,
          hasVerdict ? propagated.samplingDecision : null,
          ignored);
    }

    if (ignored == null) {
      return ROOT;
    }
    return new LLMObsParent(
        Source.NONE,
        LLMObsContext.ROOT_SPAN_ID,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        ignored);
  }

  /**
   * Whether the span is the root of its own LLMObs trace, and so the only one entitled to roll a
   * sampling decision. A parent that carries no decision is not a root: it continued a trace whose
   * caller sent none, and a local roll could drop the span while its parent is kept.
   */
  public boolean isRoot() {
    return source == Source.NONE;
  }

  @Nullable
  private static String emptyToNull(@Nullable String value) {
    return value == null || value.isEmpty() ? null : value;
  }
}
