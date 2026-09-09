package datadog.trace.llmobs;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.context.propagation.CarrierVisitor;
import datadog.context.propagation.Propagator;
import datadog.trace.api.llmobs.LLMObsContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;

/**
 * Stages the LLM Observability propagation tags onto the span context being injected, so that every
 * boundary already covered by automatic instrumentation carries LLMObs context without the
 * application having to propagate it by hand.
 *
 * <p>This propagator writes nothing to the carrier itself. It runs ahead of the tracing propagator
 * (see {@code AgentPropagation.LLMOBS_CONCERN}) and only populates the {@code _dd.p.llmobs_*}
 * fields on the span context; the tracing propagator then serializes them into {@code
 * x-datadog-tags} / {@code tracestate} along with every other propagation tag.
 *
 * <p>Values are resolved from the ambient {@link LLMObsContext} at injection time rather than being
 * written once when a span starts, and every injection rewrites the whole set — falling back to
 * whatever arrived on the inbound headers when no LLMObs context applies. That way the innermost
 * active LLMObs span always wins, leaving an LLMObs scope stops contributing its tags, and a
 * service that opens no LLMObs span of its own still forwards its caller's context — all without
 * any save/restore bookkeeping.
 */
public class LLMObsContextPropagator implements Propagator {

  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    AgentSpan span = AgentSpan.fromContext(context);
    if (span == null) {
      return;
    }
    AgentSpanContext spanContext = span.spanContext();
    if (spanContext == null) {
      return;
    }

    // Gate on trace-id consistency, the same way DDLLMObsSpan gates parent_id/session_id
    // inheritance. An LLMObs context leaked across an async boundary must not tag an outbound
    // request that belongs to an unrelated trace.
    AgentSpanContext llmObsContext = LLMObsContext.current();
    if (llmObsContext == null || llmObsContext.getTraceId() != spanContext.getTraceId()) {
      // Reset rather than return. These tags are staged on the root span context's propagation
      // tags, which the whole local trace shares, so anything an earlier injection wrote would
      // otherwise ride along on this one too — shipping a session and an agent attribution that
      // are no longer active. Reset restores the extracted values instead of clearing outright:
      // the same object also holds what came in on the wire, and a pass-through service must keep
      // forwarding its caller's context.
      spanContext.resetLLMObsContext();
      return;
    }

    spanContext.updateLLMObsContext(
        LLMObsContext.currentMlApp(),
        LLMObsContext.currentSessionId(),
        LLMObsContext.currentParentAgentSpanId(),
        LLMObsContext.currentParentAgentName(),
        String.valueOf(llmObsContext.getSpanId()));
  }

  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    // Nothing to do: the tracing propagator's codecs already parse the _dd.p.llmobs_* tags back
    // into the extracted context's propagation tags, and DDLLMObsSpan reads them from there when
    // no in-process LLMObs parent applies.
    return context;
  }
}
