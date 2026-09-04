package datadog.trace.core.propagation.ptags;

/**
 * Bundles the four LLM Observability propagation tag values ({@code ml_app}, {@code session_id},
 * parent agent span id, parent agent name) extracted from an incoming header, so they can be
 * threaded through {@link PTagsFactory.PTags} construction as a single parameter.
 */
final class LLMObsTagValues {
  static final LLMObsTagValues EMPTY = new LLMObsTagValues(null, null, null, null);

  final TagValue mlApp;
  final TagValue sessionId;
  final TagValue parentAgentSpanId;
  final TagValue parentAgentName;

  LLMObsTagValues(
      TagValue mlApp, TagValue sessionId, TagValue parentAgentSpanId, TagValue parentAgentName) {
    this.mlApp = mlApp;
    this.sessionId = sessionId;
    this.parentAgentSpanId = parentAgentSpanId;
    this.parentAgentName = parentAgentName;
  }
}
