package datadog.trace.core.propagation.ptags;

import static datadog.trace.core.propagation.ptags.PTagsFactory.PROPAGATION_ERROR_TAG_KEY;

import datadog.trace.api.ProductTraceSource;
import datadog.trace.api.llmobs.LLMObsPropagationValues;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.propagation.ptags.PTagsFactory.PTags;
import datadog.trace.core.propagation.ptags.TagElement.Encoding;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

abstract class PTagsCodec {
  private static final String PROPAGATION_ERROR_INJECT_MAX_SIZE = "inject_max_size";
  private static final String PROPAGATION_ERROR_DISABLED = "disabled";

  protected static final TagKey DECISION_MAKER_TAG = TagKey.from("dm");
  protected static final TagKey TRACE_ID_TAG = TagKey.from("tid");
  protected static final TagKey TRACE_SOURCE_TAG = TagKey.from("ts");
  protected static final TagKey DEBUG_TAG = TagKey.from("debug");
  protected static final TagKey KNUTH_SAMPLING_RATE_TAG = TagKey.from("ksr");
  protected static final TagKey ORG_PROPAGATION_MARKER_TAG = TagKey.from("opm");
  protected static final String PROPAGATION_ERROR_MALFORMED_TID = "malformed_tid ";
  protected static final String PROPAGATION_ERROR_INCONSISTENT_TID = "inconsistent_tid ";
  protected static final TagKey UPSTREAM_SERVICES_DEPRECATED_TAG = TagKey.from("upstream_services");
  protected static final TagKey LLMOBS_TRACE_ID_TAG = TagKey.from("llmobs_trace_id");
  protected static final TagKey LLMOBS_ML_APP_TAG = TagKey.from("llmobs_ml_app");
  protected static final TagKey LLMOBS_SESSION_ID_TAG = TagKey.from("llmobs_sid");
  protected static final TagKey LLMOBS_PAGENT_SPAN_ID_TAG = TagKey.from("llmobs_pagent_span_id");
  protected static final TagKey LLMOBS_PAGENT_NAME_TAG = TagKey.from("llmobs_pagent_name");
  protected static final TagKey LLMOBS_PARENT_ID_TAG = TagKey.from("llmobs_parent_id");
  protected static final TagKey LLMOBS_SAMPLE_RATE_TAG = TagKey.from("llmobs_sr");
  protected static final TagKey LLMOBS_SAMPLING_DECISION_TAG = TagKey.from("llmobs_sd");

  static String headerValue(PTagsCodec codec, PTags ptags) {
    return headerValue(codec, ptags, null);
  }

  static String headerValue(PTagsCodec codec, PTags ptags, CharSequence lastParentIdOverride) {
    return headerValue(codec, ptags, lastParentIdOverride, null);
  }

  /**
   * Encodes the header. {@code llmObsValues} are the LLM Observability values of the span being
   * injected; {@code null} writes the ones that arrived on the inbound headers instead, which is
   * what a service forwarding a request without an LLMObs span of its own should propagate.
   */
  static String headerValue(
      PTagsCodec codec,
      PTags ptags,
      CharSequence lastParentIdOverride,
      LLMObsPropagationValues llmObsValues) {
    // Neither branch extracts anything: extraction already happened in fromHeaderValue. This picks
    // which already-resolved set to write. Null means the injecting span has no LLMObs context of
    // its own — either LLM Observability is off, or no LLMObs span is active on this trace — so
    // the inbound values are forwarded untouched. Non-null replaces them with the injecting span's
    // own, which need a fit check because they have never been through a size limit.
    LLMObsTagValues llmObsTags =
        llmObsValues == null
            ? ptags.getExtractedLLMObsTagValues()
            : codec.degradeLLMObsToFit(ptags, LLMObsTagValues.from(llmObsValues));

    int estimate = codec.addLLMObsSize(codec.estimateHeaderSize(ptags), llmObsTags);
    if (estimate == 0) {
      return "";
    }

    // No encoding validation here because we don't allow arbitrary tag change
    StringBuilder sb = new StringBuilder(estimate);
    int size = codec.addLLMObsSize(codec.appendPrefix(sb, ptags, lastParentIdOverride), llmObsTags);
    if (!ptags.isPropagationTagsDisabled()) {
      if (ptags.getDecisionMakerTagValue() != null) {
        size = codec.appendTag(sb, DECISION_MAKER_TAG, ptags.getDecisionMakerTagValue(), size);
      }
      if (ptags.getTraceIdHighOrderBitsHexTagValue() != null) {
        size = codec.appendTag(sb, TRACE_ID_TAG, ptags.getTraceIdHighOrderBitsHexTagValue(), size);
      }
      if (ptags.getTraceSource() != ProductTraceSource.UNSET) {
        size =
            codec.appendTag(
                sb,
                TRACE_SOURCE_TAG,
                TagValue.from(ProductTraceSource.getBitfieldHex(ptags.getTraceSource())),
                size);
      }
      if (ptags.getDebugPropagation() != null) {
        size = codec.appendTag(sb, DEBUG_TAG, TagValue.from(ptags.getDebugPropagation()), size);
      }
      if (ptags.getKnuthSamplingRateTagValue() != null) {
        size =
            codec.appendTag(
                sb, KNUTH_SAMPLING_RATE_TAG, ptags.getKnuthSamplingRateTagValue(), size);
      }
      if (ptags.getOrgPropagationMarkerTagValue() != null) {
        size =
            codec.appendTag(
                sb, ORG_PROPAGATION_MARKER_TAG, ptags.getOrgPropagationMarkerTagValue(), size);
      }
      if (llmObsTags.traceId != null) {
        size = codec.appendTag(sb, LLMOBS_TRACE_ID_TAG, llmObsTags.traceId, size);
      }
      if (llmObsTags.mlApp != null) {
        size = codec.appendTag(sb, LLMOBS_ML_APP_TAG, llmObsTags.mlApp, size);
      }
      if (llmObsTags.sessionId != null) {
        size = codec.appendTag(sb, LLMOBS_SESSION_ID_TAG, llmObsTags.sessionId, size);
      }
      if (llmObsTags.parentAgentSpanId != null) {
        size = codec.appendTag(sb, LLMOBS_PAGENT_SPAN_ID_TAG, llmObsTags.parentAgentSpanId, size);
      }
      if (llmObsTags.parentAgentName != null) {
        size = codec.appendTag(sb, LLMOBS_PAGENT_NAME_TAG, llmObsTags.parentAgentName, size);
      }
      if (llmObsTags.parentId != null) {
        size = codec.appendTag(sb, LLMOBS_PARENT_ID_TAG, llmObsTags.parentId, size);
      }
      if (llmObsTags.sampleRate != null) {
        size = codec.appendTag(sb, LLMOBS_SAMPLE_RATE_TAG, llmObsTags.sampleRate, size);
      }
      if (llmObsTags.samplingDecision != null) {
        size = codec.appendTag(sb, LLMOBS_SAMPLING_DECISION_TAG, llmObsTags.samplingDecision, size);
      }
      Iterator<TagElement> it = ptags.getTagPairs().iterator();
      while (it.hasNext() && !codec.isTooLarge(sb, size)) {
        TagElement tagKey = it.next();
        TagElement tagValue = it.next();
        size = codec.appendTag(sb, tagKey, tagValue, size);
      }
    }
    size = codec.appendSuffix(sb, ptags, size);
    if (codec.isTooLarge(sb, size)) {
      return null;
    } else {
      return codec.isEmpty(sb, size) ? null : sb.toString();
    }
  }

  static void fillTagMap(PTags propagationTags, Map<String, String> tagMap) {
    LLMObsTagValues llmObsTags = propagationTags.getExtractedLLMObsTagValues();
    // Count the LLMObs tags this fills in below, which getXDatadogTagsSize() no longer holds.
    int newSize = calcLLMObsSize(propagationTags.getXDatadogTagsSize(), llmObsTags);

    if (newSize > propagationTags.getxDatadogTagsLimit()) {
      // Outgoing x-datadog-tags value length exceeds the configured limit
      // Set _dd.propagation_error:inject_max_size if the configured limit is greater than zero,
      // else set _dd.propagation_error:disabled
      if (propagationTags.isPropagationTagsDisabled()) {
        tagMap.put(PROPAGATION_ERROR_TAG_KEY, PROPAGATION_ERROR_DISABLED);
      } else {
        tagMap.put(PROPAGATION_ERROR_TAG_KEY, PROPAGATION_ERROR_INJECT_MAX_SIZE);
      }
      return;
    }

    Iterator<TagElement> it = propagationTags.getTagPairs().iterator();
    while (it.hasNext()) {
      TagElement tagKey = it.next();
      TagElement tagValue = it.next();
      tagMap.put(
          tagKey.forType(Encoding.DATADOG).toString(),
          tagValue.forType(Encoding.DATADOG).toString());
    }
    if (propagationTags.getDecisionMakerTagValue() != null) {
      tagMap.put(
          DECISION_MAKER_TAG.forType(Encoding.DATADOG).toString(),
          propagationTags.getDecisionMakerTagValue().forType(Encoding.DATADOG).toString());
    }
    if (propagationTags.getTraceSource() != ProductTraceSource.UNSET) {
      tagMap.put(
          TRACE_SOURCE_TAG.forType(Encoding.DATADOG).toString(),
          TagValue.from(ProductTraceSource.getBitfieldHex(propagationTags.getTraceSource()))
              .forType(Encoding.DATADOG)
              .toString());
    }
    if (propagationTags.getDebugPropagation() != null) {
      tagMap.put(
          DEBUG_TAG.forType(Encoding.DATADOG).toString(), propagationTags.getDebugPropagation());
    }
    if (propagationTags.getKnuthSamplingRateTagValue() != null) {
      tagMap.put(
          KNUTH_SAMPLING_RATE_TAG.forType(Encoding.DATADOG).toString(),
          propagationTags.getKnuthSamplingRateTagValue().forType(Encoding.DATADOG).toString());
    }
    if (propagationTags.getOrgPropagationMarkerTagValue() != null) {
      tagMap.put(
          ORG_PROPAGATION_MARKER_TAG.forType(Encoding.DATADOG).toString(),
          propagationTags.getOrgPropagationMarkerTagValue().forType(Encoding.DATADOG).toString());
    }
    if (propagationTags.getTraceIdHighOrderBitsHexTagValue() != null) {
      tagMap.put(
          TRACE_ID_TAG.forType(Encoding.DATADOG).toString(),
          propagationTags
              .getTraceIdHighOrderBitsHexTagValue()
              .forType(Encoding.DATADOG)
              .toString());
    }
    if (llmObsTags.traceId != null) {
      tagMap.put(
          LLMOBS_TRACE_ID_TAG.forType(Encoding.DATADOG).toString(),
          llmObsTags.traceId.forType(Encoding.DATADOG).toString());
    }
    if (llmObsTags.mlApp != null) {
      tagMap.put(
          LLMOBS_ML_APP_TAG.forType(Encoding.DATADOG).toString(),
          llmObsTags.mlApp.forType(Encoding.DATADOG).toString());
    }
    if (llmObsTags.sessionId != null) {
      tagMap.put(
          LLMOBS_SESSION_ID_TAG.forType(Encoding.DATADOG).toString(),
          llmObsTags.sessionId.forType(Encoding.DATADOG).toString());
    }
    if (llmObsTags.parentAgentSpanId != null) {
      tagMap.put(
          LLMOBS_PAGENT_SPAN_ID_TAG.forType(Encoding.DATADOG).toString(),
          llmObsTags.parentAgentSpanId.forType(Encoding.DATADOG).toString());
    }
    if (llmObsTags.parentAgentName != null) {
      tagMap.put(
          LLMOBS_PAGENT_NAME_TAG.forType(Encoding.DATADOG).toString(),
          llmObsTags.parentAgentName.forType(Encoding.DATADOG).toString());
    }
    if (llmObsTags.parentId != null) {
      tagMap.put(
          LLMOBS_PARENT_ID_TAG.forType(Encoding.DATADOG).toString(),
          llmObsTags.parentId.forType(Encoding.DATADOG).toString());
    }
    if (llmObsTags.sampleRate != null) {
      tagMap.put(
          LLMOBS_SAMPLE_RATE_TAG.forType(Encoding.DATADOG).toString(),
          llmObsTags.sampleRate.forType(Encoding.DATADOG).toString());
    }
    if (llmObsTags.samplingDecision != null) {
      tagMap.put(
          LLMOBS_SAMPLING_DECISION_TAG.forType(Encoding.DATADOG).toString(),
          llmObsTags.samplingDecision.forType(Encoding.DATADOG).toString());
    }
    if (propagationTags.getError() != null) {
      tagMap.put(PROPAGATION_ERROR_TAG_KEY, propagationTags.getError());
    }
  }

  static int calcXDatadogTagsSize(List<TagElement> tagPairs) {
    int size = 0;
    int pl = Encoding.DATADOG.getPrefixLength();
    boolean key = true;
    for (CharSequence tagPair : tagPairs) {
      if (key) {
        size += pl;
      }
      key = !key;
      size += tagPair.length();
      size += 1; // tag or key separator
    }
    return size == 0 ? 0 : size - 1; // exclude last separator
  }

  /** Adds what the eight LLM Observability tags cost in {@code x-datadog-tags} to {@code size}. */
  static int calcLLMObsSize(int size, LLMObsTagValues llmObsTags) {
    size = calcXDatadogTagsSize(size, LLMOBS_TRACE_ID_TAG, llmObsTags.traceId);
    size = calcXDatadogTagsSize(size, LLMOBS_ML_APP_TAG, llmObsTags.mlApp);
    size = calcXDatadogTagsSize(size, LLMOBS_SESSION_ID_TAG, llmObsTags.sessionId);
    size = calcXDatadogTagsSize(size, LLMOBS_PAGENT_SPAN_ID_TAG, llmObsTags.parentAgentSpanId);
    size = calcXDatadogTagsSize(size, LLMOBS_PAGENT_NAME_TAG, llmObsTags.parentAgentName);
    size = calcXDatadogTagsSize(size, LLMOBS_PARENT_ID_TAG, llmObsTags.parentId);
    size = calcXDatadogTagsSize(size, LLMOBS_SAMPLE_RATE_TAG, llmObsTags.sampleRate);
    size = calcXDatadogTagsSize(size, LLMOBS_SAMPLING_DECISION_TAG, llmObsTags.samplingDecision);
    return size;
  }

  static int calcXDatadogTagsSize(int size, TagKey tagKey, TagValue tagValue) {
    if (tagValue != null) {
      if (size > 0) {
        // tag separator
        size += 1;
      }
      size += tagKey.length();
      // tag key separator
      size += 1;
      size += tagValue.length();
      size += Encoding.DATADOG.getPrefixLength();
    }
    return size;
  }

  /**
   * Adds the LLM Observability tags to a running {@code x-datadog-tags} size. Only {@link
   * DatadogPTagsCodec} needs this: its {@code size} is a total computed up front, while {@link
   * W3CPTagsCodec} accumulates as it appends.
   */
  protected int addLLMObsSize(int size, LLMObsTagValues llmObsTags) {
    return size;
  }

  /**
   * Trims the LLM Observability values until they fit the carrier, or returns them unchanged when
   * the carrier degrades on its own. See {@link DatadogPTagsCodec#degradeLLMObsToFit}.
   */
  protected LLMObsTagValues degradeLLMObsToFit(PTags ptags, LLMObsTagValues llmObsTags) {
    return llmObsTags;
  }

  abstract PropagationTags fromHeaderValue(PTagsFactory tagsFactory, String value);

  protected abstract int estimateHeaderSize(PTags pTags);

  protected abstract int appendPrefix(StringBuilder sb, PTags ptags);

  /**
   * Encode the prefix, using {@code lastParentIdOverride} for the W3C {@code p:} when non-null
   * (inject-time). Codecs without a last-parent-id (e.g. Datadog) ignore the override.
   */
  protected int appendPrefix(StringBuilder sb, PTags ptags, CharSequence lastParentIdOverride) {
    return appendPrefix(sb, ptags);
  }

  protected abstract int appendTag(StringBuilder sb, TagElement key, TagElement value, int size);

  protected abstract int appendSuffix(StringBuilder sb, PTags ptags, int size);

  protected abstract boolean isTooLarge(StringBuilder sb, int size);

  protected abstract boolean isEmpty(StringBuilder sb, int size);

  protected static boolean validateTagValue(TagKey tagKey, TagValue tagValue) {
    if (tagKey.equals(DECISION_MAKER_TAG) && !validateDecisionMakerTag(tagValue)) {
      return false;
    } else if (tagKey.equals(TRACE_ID_TAG) && !validateTraceId(tagValue)) {
      return false;
    } else if (tagKey.equals(TRACE_SOURCE_TAG) && !validateTraceSourceTagValue(tagValue)) {
      return false;
    }
    return true;
  }

  /**
   * Validates the dm tag format with next eBNF:
   *
   * <pre>
   *   _dd.p.dm = [ service hash ], "-", sampling mechanism;
   *
   *   digit = "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" ;
   *   hexadecimal digit = digit | "a" | "b" | "c" | "d" | "e" | "f" ;
   *
   *   service hash = 10 * hexadecimal digit;
   *   sampling mechanism = digit, { digit };
   * </pre>
   */
  private static boolean validateDecisionMakerTag(TagValue value) {
    int sepPos = value.indexOf('-');
    if (sepPos < 0) {
      // missing separator
      return false;
    }
    if (sepPos != 0 && sepPos != 10) {
      // invalid service hash length
      return false;
    }
    int samplingMechanismPos = sepPos + 1;
    int len = value.length();
    if (samplingMechanismPos == len) {
      // missing sampling mechanism
      return false;
    }
    for (int i = 0; i < sepPos; i++) {
      if (!isHexDigit(value.charAt(i))) {
        // invalid service hash char
        return false;
      }
    }
    for (int i = samplingMechanismPos; i < len; i++) {
      if (!isDigit(value.charAt(i))) {
        // invalid sampling mechanism
        return false;
      }
    }
    return true;
  }

  private static boolean validateTraceId(TagValue value) {
    // invalid length
    if (value.length() != 16) {
      return false;
    }
    for (int i = 0; i < 16; i++) {
      // invalid trace id character
      if (!isHexDigit(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static boolean validateTraceSourceTagValue(TagValue value) {
    // Ensure the string is not null and has a length between 2 and 8
    if (value == null || value.length() < 2 || value.length() > 8) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      // Ensure each character is a valid hex digit
      if (!isHexDigitCaseInsensitive(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  protected static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  protected static boolean isHexDigit(char c) {
    return c >= 'a' && c <= 'f' || isDigit(c);
  }

  protected static boolean isHexDigitCaseInsensitive(char c) {
    return isHexDigit(c) || c >= 'A' && c <= 'F';
  }
}
