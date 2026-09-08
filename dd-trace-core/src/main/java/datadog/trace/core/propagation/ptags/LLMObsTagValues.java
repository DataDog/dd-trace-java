package datadog.trace.core.propagation.ptags;

import java.util.Objects;

/**
 * Bundles the five LLM Observability propagation tag values ({@code ml_app}, {@code session_id},
 * parent agent span id, parent agent name, parent span id) extracted from an incoming header, so
 * they can be threaded through {@link PTagsFactory.PTags} construction as a single parameter.
 *
 * <p>Never {@code null}: use {@link #EMPTY} to say "no LLM Observability tags", and obtain
 * instances through {@link #of} so that the common case — an incoming request carrying none of
 * these tags, which is every request in a service not using LLM Observability — reuses {@code
 * EMPTY} rather than allocating.
 */
final class LLMObsTagValues {
  static final LLMObsTagValues EMPTY = new LLMObsTagValues(null, null, null, null, null);

  final TagValue mlApp;
  final TagValue sessionId;
  final TagValue parentAgentSpanId;
  final TagValue parentAgentName;
  final TagValue parentId;

  /** Returns {@link #EMPTY} when every value is {@code null}, otherwise a new bundle. */
  static LLMObsTagValues of(
      TagValue mlApp,
      TagValue sessionId,
      TagValue parentAgentSpanId,
      TagValue parentAgentName,
      TagValue parentId) {
    if (mlApp == null
        && sessionId == null
        && parentAgentSpanId == null
        && parentAgentName == null
        && parentId == null) {
      return EMPTY;
    }
    return new LLMObsTagValues(mlApp, sessionId, parentAgentSpanId, parentAgentName, parentId);
  }

  private LLMObsTagValues(
      TagValue mlApp,
      TagValue sessionId,
      TagValue parentAgentSpanId,
      TagValue parentAgentName,
      TagValue parentId) {
    this.mlApp = mlApp;
    this.sessionId = sessionId;
    this.parentAgentSpanId = parentAgentSpanId;
    this.parentAgentName = parentAgentName;
    this.parentId = parentId;
  }

  /**
   * Whether {@code other} carries the same five values. Used to skip cache invalidation when an
   * injection re-stages tags a span already has; not {@code equals} because these are never used as
   * map keys and identity equality is the useful default elsewhere in this package.
   */
  boolean sameAs(LLMObsTagValues other) {
    return this == other
        || (Objects.equals(mlApp, other.mlApp)
            && Objects.equals(sessionId, other.sessionId)
            && Objects.equals(parentAgentSpanId, other.parentAgentSpanId)
            && Objects.equals(parentAgentName, other.parentAgentName)
            && Objects.equals(parentId, other.parentId));
  }
}
