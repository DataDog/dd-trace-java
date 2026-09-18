package datadog.trace.core.propagation.ptags;

import java.util.Objects;

/**
 * Bundles the seven LLM Observability propagation tag values as a single parameter.
 *
 * <p>Never {@code null}: use {@link #EMPTY} to say "no LLM Observability tags", and obtain
 * instances through {@link #of} so that the common case — an incoming request carrying none of
 * these tags, which is every request in a service not using LLM Observability — reuses {@code
 * EMPTY} rather than allocating.
 */
final class LLMObsTagValues {
  static final LLMObsTagValues EMPTY =
      new LLMObsTagValues(null, null, null, null, null, null, null);

  final TagValue mlApp;
  final TagValue sessionId;
  final TagValue parentAgentSpanId;
  final TagValue parentAgentName;
  final TagValue parentId;
  final TagValue sampleRate;
  final TagValue samplingDecision;

  /** Returns {@link #EMPTY} when every value is {@code null}, otherwise a new bundle. */
  static LLMObsTagValues of(
      TagValue mlApp,
      TagValue sessionId,
      TagValue parentAgentSpanId,
      TagValue parentAgentName,
      TagValue parentId,
      TagValue sampleRate,
      TagValue samplingDecision) {
    if (mlApp == null
        && sessionId == null
        && parentAgentSpanId == null
        && parentAgentName == null
        && parentId == null
        && sampleRate == null
        && samplingDecision == null) {
      return EMPTY;
    }
    return new LLMObsTagValues(
        mlApp,
        sessionId,
        parentAgentSpanId,
        parentAgentName,
        parentId,
        sampleRate,
        samplingDecision);
  }

  /**
   * A copy with the agent attribution replaced. Used to degrade attribution when the full tag set
   * would overflow the {@code x-datadog-tags} budget; every other value is preserved.
   */
  LLMObsTagValues withAgentAttribution(TagValue parentAgentSpanId, TagValue parentAgentName) {
    return of(
        mlApp,
        sessionId,
        parentAgentSpanId,
        parentAgentName,
        parentId,
        sampleRate,
        samplingDecision);
  }

  private LLMObsTagValues(
      TagValue mlApp,
      TagValue sessionId,
      TagValue parentAgentSpanId,
      TagValue parentAgentName,
      TagValue parentId,
      TagValue sampleRate,
      TagValue samplingDecision) {
    this.mlApp = mlApp;
    this.sessionId = sessionId;
    this.parentAgentSpanId = parentAgentSpanId;
    this.parentAgentName = parentAgentName;
    this.parentId = parentId;
    this.sampleRate = sampleRate;
    this.samplingDecision = samplingDecision;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof LLMObsTagValues)) {
      return false;
    }
    LLMObsTagValues other = (LLMObsTagValues) o;
    return Objects.equals(mlApp, other.mlApp)
        && Objects.equals(sessionId, other.sessionId)
        && Objects.equals(parentAgentSpanId, other.parentAgentSpanId)
        && Objects.equals(parentAgentName, other.parentAgentName)
        && Objects.equals(parentId, other.parentId)
        && Objects.equals(sampleRate, other.sampleRate)
        && Objects.equals(samplingDecision, other.samplingDecision);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        mlApp,
        sessionId,
        parentAgentSpanId,
        parentAgentName,
        parentId,
        sampleRate,
        samplingDecision);
  }
}
