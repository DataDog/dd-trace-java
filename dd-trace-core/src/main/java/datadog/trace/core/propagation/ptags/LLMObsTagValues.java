package datadog.trace.core.propagation.ptags;

import datadog.trace.api.llmobs.LLMObsContext;
import datadog.trace.api.llmobs.LLMObsPropagationValues;

/**
 * Bundles the eight LLM Observability propagation tag values as a single parameter.
 *
 * <p>Never {@code null}: use {@link #EMPTY} to say "no LLM Observability tags", and obtain
 * instances through {@link #of} so that the common case — an incoming request carrying none of
 * these tags, which is every request in a service not using LLM Observability — reuses {@code
 * EMPTY} rather than allocating.
 */
final class LLMObsTagValues {
  static final LLMObsTagValues EMPTY =
      new LLMObsTagValues(null, null, null, null, null, null, null, null);

  final TagValue traceId;
  final TagValue mlApp;
  final TagValue sessionId;
  final TagValue parentAgentSpanId;
  final TagValue parentAgentName;
  final TagValue parentId;
  final TagValue sampleRate;
  final TagValue samplingDecision;

  /** Returns {@link #EMPTY} when every value is {@code null}, otherwise a new bundle. */
  static LLMObsTagValues of(
      TagValue traceId,
      TagValue mlApp,
      TagValue sessionId,
      TagValue parentAgentSpanId,
      TagValue parentAgentName,
      TagValue parentId,
      TagValue sampleRate,
      TagValue samplingDecision) {
    if (traceId == null
        && mlApp == null
        && sessionId == null
        && parentAgentSpanId == null
        && parentAgentName == null
        && parentId == null
        && sampleRate == null
        && samplingDecision == null) {
      return EMPTY;
    }
    return new LLMObsTagValues(
        traceId,
        mlApp,
        sessionId,
        parentAgentSpanId,
        parentAgentName,
        parentId,
        sampleRate,
        samplingDecision);
  }

  /** The wire-level values an injection supplied, encoded as tags. */
  static LLMObsTagValues from(LLMObsPropagationValues values) {
    return of(
        toUniqueTagValue(values.traceId),
        toTagValue(values.mlApp),
        toTagValue(values.sessionId),
        toUniqueTagValue(values.parentAgentSpanId),
        toTagValue(values.parentAgentName),
        toUniqueTagValue(values.parentId),
        toTagValue(values.sampleRate),
        toTagValue(values.samplingDecision));
  }

  /**
   * Wraps a non-empty value as a {@link TagValue}, or {@code null} if it is empty or cannot be
   * represented in {@code x-datadog-tags}.
   *
   * <p>Unlike every other {@code _dd.p.*} tag, these values come from the application rather than
   * the tracer, so they have to be checked before they reach the wire.
   */
  static TagValue toTagValue(CharSequence value) {
    if (value == null || value.length() == 0 || !isRepresentable(value)) {
      return null;
    }
    return TagValue.from(value);
  }

  /**
   * Like {@link #toTagValue}, for an id unique to one trace or span, which skips the tag value
   * cache unless it's the {@link #isRootMarker root marker}.
   */
  static TagValue toUniqueTagValue(CharSequence value) {
    if (value == null || value.length() == 0 || !isRepresentable(value)) {
      return null;
    }
    return isRootMarker(value, 0, value.length()) ? TagValue.from(value) : TagValue.uncached(value);
  }

  /**
   * Whether {@code s} between {@code start} and {@code end} is {@link LLMObsContext#ROOT_SPAN_ID},
   * which LLM Observability sends in place of a parent id at every root, so it repeats.
   */
  static boolean isRootMarker(CharSequence s, int start, int end) {
    String marker = LLMObsContext.ROOT_SPAN_ID;
    if (s == null || end - start != marker.length()) {
      return false;
    }
    for (int i = 0; i < marker.length(); i++) {
      if (s.charAt(start + i) != marker.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether every character survives each carrier the value can travel on: printable ASCII, no
   * {@code ,} (the {@code x-datadog-tags} separator), nothing the {@code tracestate} conversion
   * rewrites, and no {@code "} or {@code \} — AWS messaging carries these headers in a {@code
   * _datadog} JSON attribute that is written and parsed without escaping.
   */
  private static boolean isRepresentable(CharSequence value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < ' '
          || c > '~'
          || c == ','
          || c == '"'
          || c == '\\'
          || !TagValue.survivesW3CRoundTrip(c)) {
        return false;
      }
    }
    return true;
  }

  /**
   * A copy with the agent attribution replaced. Used to degrade attribution when the full tag set
   * would overflow the {@code x-datadog-tags} budget; every other value is preserved.
   */
  LLMObsTagValues withAgentAttribution(TagValue parentAgentSpanId, TagValue parentAgentName) {
    return of(
        traceId,
        mlApp,
        sessionId,
        parentAgentSpanId,
        parentAgentName,
        parentId,
        sampleRate,
        samplingDecision);
  }

  private LLMObsTagValues(
      TagValue traceId,
      TagValue mlApp,
      TagValue sessionId,
      TagValue parentAgentSpanId,
      TagValue parentAgentName,
      TagValue parentId,
      TagValue sampleRate,
      TagValue samplingDecision) {
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
