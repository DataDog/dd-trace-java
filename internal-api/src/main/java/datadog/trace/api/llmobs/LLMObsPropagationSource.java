package datadog.trace.api.llmobs;

import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import javax.annotation.Nullable;

/**
 * Supplies the LLM Observability propagation tag values for an outbound request.
 *
 * <p>Lets the injection codecs obtain those values without knowing how LLM Observability tracks
 * them. Registered by {@code LLMObsSystem} at startup through {@link
 * LLMObsInternal#setPropagationSource}; when LLM Observability is disabled nothing registers one
 * and the codecs skip the lookup entirely.
 */
public interface LLMObsPropagationSource {

  /**
   * The values to inject alongside {@code spanContext}, or {@code null} when no local LLM
   * Observability context applies to it — in which case the codec forwards whatever arrived on the
   * inbound headers instead, so a service that opens no LLMObs span of its own still passes its
   * caller's context along.
   */
  @Nullable
  LLMObsPropagationValues valuesFor(AgentSpanContext spanContext);
}
