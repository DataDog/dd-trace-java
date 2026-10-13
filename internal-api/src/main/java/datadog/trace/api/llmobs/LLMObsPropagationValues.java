package datadog.trace.api.llmobs;

import javax.annotation.Nullable;

/**
 * The LLM Observability context of one request, in either direction: the values to write into the
 * {@code _dd.p.llmobs_*} propagation tags of an outbound request, or the values that arrived on an
 * inbound one. Every field may be {@code null} — on the way out that means the tag is not written,
 * on the way in that it did not arrive.
 *
 * <p>On the outbound side the values are resolved fresh for each injection and passed to the
 * serializer as an argument, never stored on the span context: they belong to the span doing the
 * injecting, while propagation tags are shared by every span in a local trace, so staging them
 * there lets concurrent injections overwrite each other's context. Same reasoning as the W3C {@code
 * p:} last-parent-id, which is threaded to the serializer for exactly this reason.
 *
 * <p>On the inbound side an instance is built on demand by {@code
 * AgentSpanContext#getExtractedLLMObsValues()}, with any {@code tracestate} substitutions already
 * undone, so consumers read the values as the calling application wrote them.
 */
public final class LLMObsPropagationValues {
  @Nullable public final String traceId;
  @Nullable public final String mlApp;
  @Nullable public final String sessionId;
  @Nullable public final String parentAgentSpanId;
  @Nullable public final String parentAgentName;
  @Nullable public final String parentId;
  @Nullable public final String sampleRate;
  @Nullable public final String samplingDecision;

  public LLMObsPropagationValues(
      @Nullable String traceId,
      @Nullable String mlApp,
      @Nullable String sessionId,
      @Nullable String parentAgentSpanId,
      @Nullable String parentAgentName,
      @Nullable String parentId,
      @Nullable String sampleRate,
      @Nullable String samplingDecision) {
    this.traceId = traceId;
    this.mlApp = mlApp;
    this.sessionId = sessionId;
    this.parentAgentSpanId = parentAgentSpanId;
    this.parentAgentName = parentAgentName;
    this.parentId = parentId;
    this.sampleRate = sampleRate;
    this.samplingDecision = samplingDecision;
  }
}
