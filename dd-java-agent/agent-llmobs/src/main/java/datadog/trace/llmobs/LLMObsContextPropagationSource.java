package datadog.trace.llmobs;

import datadog.trace.api.llmobs.LLMObsContext;
import datadog.trace.api.llmobs.LLMObsPropagationSource;
import datadog.trace.api.llmobs.LLMObsPropagationValues;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.llmobs.domain.LLMObsTraceId;
import javax.annotation.Nullable;

/**
 * Resolves the LLM Observability propagation tag values for an outbound request from the ambient
 * {@link LLMObsContext}.
 *
 * <p>Runs at injection rather than when a span starts, so the innermost active LLMObs span always
 * wins and leaving an LLMObs scope stops contributing its tags — with no save/restore bookkeeping,
 * because nothing is stored: the values are returned to the caller and serialized immediately.
 */
public final class LLMObsContextPropagationSource implements LLMObsPropagationSource {

  @Override
  @Nullable
  public LLMObsPropagationValues valuesFor(AgentSpanContext spanContext) {
    if (spanContext == null) {
      return null;
    }
    // Gate on trace-id consistency, the same way DDLLMObsSpan gates parent_id/session_id
    // inheritance. An LLMObs context leaked across an async boundary must not tag an outbound
    // request that belongs to an unrelated trace. Returning null here means the codec forwards the
    // inbound values instead, which is what a pass-through service should do.
    AgentSpanContext llmObsContext = LLMObsContext.current();
    if (llmObsContext == null || !llmObsContext.getTraceId().equals(spanContext.getTraceId())) {
      return null;
    }
    return new LLMObsPropagationValues(
        // Stored as hex in-process and in the span payload, carried as decimal on the wire.
        LLMObsTraceId.toWire(LLMObsContext.currentTraceId()),
        LLMObsContext.currentMlApp(),
        LLMObsContext.currentSessionId(),
        LLMObsContext.currentParentAgentSpanId(),
        LLMObsContext.currentParentAgentName(),
        String.valueOf(llmObsContext.getSpanId()),
        LLMObsContext.currentSampleRate(),
        LLMObsContext.currentSamplingDecision());
  }
}
