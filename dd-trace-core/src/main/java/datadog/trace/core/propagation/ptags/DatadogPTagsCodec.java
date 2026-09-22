package datadog.trace.core.propagation.ptags;

import datadog.logging.RatelimitedLogger;
import datadog.trace.api.ProductTraceSource;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.propagation.ptags.PTagsFactory.PTags;
import datadog.trace.core.propagation.ptags.TagElement.Encoding;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;
import org.slf4j.LoggerFactory;

/** Captures configuration required for PropagationTags logic */
final class DatadogPTagsCodec extends PTagsCodec {
  private static final RatelimitedLogger log =
      new RatelimitedLogger(LoggerFactory.getLogger(DatadogPTagsCodec.class), 5, TimeUnit.MINUTES);
  private static final String PROPAGATION_ERROR_EXTRACT_MAX_SIZE = "extract_max_size";
  private static final String PROPAGATION_ERROR_DECODING_ERROR = "decoding_error";
  private static final char TAGS_SEPARATOR = ',';
  private static final char TAG_KEY_SEPARATOR = '=';
  private static final int MIN_ALLOWED_CHAR = 32;
  private static final int MAX_ALLOWED_CHAR = 126;

  /** What a {@code _dd.p.llmobs_pagent_name=} entry costs before its value: {@code ,_dd.p.k=}. */
  private static final int PAGENT_NAME_ENTRY_OVERHEAD =
      1 + Encoding.DATADOG.getPrefixLength() + LLMOBS_PAGENT_NAME_TAG.length() + 1;

  private final int xDatadogTagsLimit;

  DatadogPTagsCodec(int xDatadogTagsLimit) {
    this.xDatadogTagsLimit = xDatadogTagsLimit;
  }

  /**
   * Parses a header value with next eBNF:
   *
   * <pre>
   *   tagset = ( tag, { ",", tag } ) | "";
   *   tag = ( identifier - space or equal ), "=", identifier;
   *   identifier = allowed characters, { allowed characters };
   *   allowed characters = ( ? ASCII characters 32-126 ? - comma );
   *   space or equal = " " | "=";
   *   comma = ",";
   * </pre>
   *
   * All tags prefixed with `_dd.p.` are extracted from tagSet except for `_dd.p.upstream_services`.
   * TagSet that doesn't respect the format will be dropped and a warning will be logged.
   *
   * @return a PropagationTags containing only _dd.p.* tags or an error if the header value is
   *     invalid
   */
  @Override
  PropagationTags fromHeaderValue(PTagsFactory tagsFactory, String value) {
    if (value == null) {
      return tagsFactory.empty();
    }
    if (value.length() > xDatadogTagsLimit) {
      // Incoming x-datadog-tags value length exceeds datadogTagsLimit
      // Set _dd.propagation_error:extract_max_size
      return tagsFactory.createInvalid(PROPAGATION_ERROR_EXTRACT_MAX_SIZE);
    }

    List<TagElement> tagPairs = null;
    int len = value.length();
    int tagPos = 0;
    TagValue decisionMakerTagValue = null;
    TagValue traceIdTagValue = null;
    int traceSource = 0;
    TagValue orgPropagationMarkerTagValue = null;
    TagValue llmObsTraceIdTagValue = null;
    TagValue llmObsMlAppTagValue = null;
    TagValue llmObsSessionIdTagValue = null;
    TagValue llmObsParentAgentSpanIdTagValue = null;
    TagValue llmObsParentAgentNameTagValue = null;
    TagValue llmObsParentIdTagValue = null;
    TagValue llmObsSampleRateTagValue = null;
    TagValue llmObsSamplingDecisionTagValue = null;
    while (tagPos < len) {
      int tagKeyEndsAt =
          validateCharsUntilSeparatorOrEnd(
              value, tagPos, TAG_KEY_SEPARATOR, DatadogPTagsCodec::isAllowedKeyChar);
      if (tagKeyEndsAt < 0 || tagKeyEndsAt == len) {
        log.warn("Invalid datadog tags header value: '{}' at {}", value, tagPos);
        return tagsFactory.createInvalid(PROPAGATION_ERROR_DECODING_ERROR);
      }
      int tagValuePos = tagKeyEndsAt + 1;
      int tagValueEndsAt =
          validateCharsUntilSeparatorOrEnd(
              value, tagValuePos, TAGS_SEPARATOR, DatadogPTagsCodec::isAllowedValueChar);
      if (tagValueEndsAt < 0) {
        log.warn("Invalid datadog tags header value: '{}' at {}", value, tagKeyEndsAt);
        return tagsFactory.createInvalid(PROPAGATION_ERROR_DECODING_ERROR);
      }
      TagKey tagKey = TagKey.from(Encoding.DATADOG, value, tagPos, tagKeyEndsAt);
      TagValue tagValue = TagValue.from(Encoding.DATADOG, value, tagValuePos, tagValueEndsAt);
      if (tagKey != null) {
        if (!tagKey.equals(UPSTREAM_SERVICES_DEPRECATED_TAG)) {
          if (!validateTagValue(tagKey, tagValue)) {
            log.warn(
                "Invalid datadog tags header value: '{}' invalid tag value at {}",
                value,
                tagValuePos);
            if (tagKey.equals(TRACE_ID_TAG)) {
              return tagsFactory.createInvalid(PROPAGATION_ERROR_MALFORMED_TID + tagValue);
            }
            return tagsFactory.createInvalid(PROPAGATION_ERROR_DECODING_ERROR);
          }
          if (tagKey.equals(DECISION_MAKER_TAG)) {
            decisionMakerTagValue = tagValue;
          } else if (tagKey.equals(TRACE_ID_TAG)) {
            traceIdTagValue = tagValue;
          } else if (tagKey.equals(TRACE_SOURCE_TAG)) {
            traceSource = ProductTraceSource.parseBitfieldHex(tagValue.toString());
          } else if (tagKey.equals(ORG_PROPAGATION_MARKER_TAG)) {
            orgPropagationMarkerTagValue = tagValue;
          } else if (tagKey.equals(LLMOBS_TRACE_ID_TAG)) {
            llmObsTraceIdTagValue = tagValue;
          } else if (tagKey.equals(LLMOBS_ML_APP_TAG)) {
            llmObsMlAppTagValue = tagValue;
          } else if (tagKey.equals(LLMOBS_SESSION_ID_TAG)) {
            llmObsSessionIdTagValue = tagValue;
          } else if (tagKey.equals(LLMOBS_PAGENT_SPAN_ID_TAG)) {
            llmObsParentAgentSpanIdTagValue = tagValue;
          } else if (tagKey.equals(LLMOBS_PAGENT_NAME_TAG)) {
            llmObsParentAgentNameTagValue = tagValue;
          } else if (tagKey.equals(LLMOBS_PARENT_ID_TAG)) {
            llmObsParentIdTagValue = tagValue;
          } else if (tagKey.equals(LLMOBS_SAMPLE_RATE_TAG)) {
            llmObsSampleRateTagValue = tagValue;
          } else if (tagKey.equals(LLMOBS_SAMPLING_DECISION_TAG)) {
            llmObsSamplingDecisionTagValue = tagValue;
          } else {
            if (tagPairs == null) {
              // This is roughly the size of a two element linked list but can hold six
              tagPairs = new ArrayList<>(6);
            }
            tagPairs.add(tagKey);
            tagPairs.add(tagValue);
          }
        }
      }
      tagPos = tagValueEndsAt + 1;
    }
    return tagsFactory.createValid(
        tagPairs,
        decisionMakerTagValue,
        traceIdTagValue,
        traceSource,
        orgPropagationMarkerTagValue,
        LLMObsTagValues.of(
            llmObsTraceIdTagValue,
            llmObsMlAppTagValue,
            llmObsSessionIdTagValue,
            llmObsParentAgentSpanIdTagValue,
            llmObsParentAgentNameTagValue,
            llmObsParentIdTagValue,
            llmObsSampleRateTagValue,
            llmObsSamplingDecisionTagValue));
  }

  @Override
  protected int estimateHeaderSize(PTags pTags) {
    return pTags.getXDatadogTagsSize();
  }

  @Override
  protected int addLLMObsSize(int size, LLMObsTagValues llmObsTags) {
    return calcLLMObsSize(size, llmObsTags);
  }

  /**
   * Trims agent attribution until the {@code x-datadog-tags} tag set fits its configured limit.
   *
   * <p>This codec is all-or-nothing: one byte over the limit and {@link PTagsCodec#headerValue}
   * returns {@code null}, dropping the whole header — the APM tags along with ml_app, session and
   * parent_id. Agent attribution is the only part of the set with a user-supplied, unbounded value
   * (an agent's name), so it is also the only part worth sacrificing to keep the rest. The ladder
   * mirrors {@code _stamp_agent_attribution} in dd-trace-py:
   *
   * <ol>
   *   <li>id and full name, when they fit;
   *   <li>id and a name truncated to the remaining room;
   *   <li>id alone, when no room is left for any of the name;
   *   <li>neither, when even the id overflows.
   * </ol>
   *
   * <p>If the set is still too large with attribution gone, the overflow is somewhere this can't
   * help and the header drops as before. Unlike dd-trace-py, which reserves headroom for a {@code
   * _dd.p.tid} that is added after its check runs, every tag is counted here, so the full limit is
   * available.
   *
   * <p>{@link W3CPTagsCodec} needs no equivalent: it rolls back any single tag that would overflow
   * the tracestate and keeps going, so it degrades on its own.
   */
  @Override
  protected LLMObsTagValues degradeLLMObsToFit(PTags ptags, LLMObsTagValues tags) {
    if (tags.parentAgentSpanId == null) {
      // Nothing to degrade: a name is only ever written alongside an id.
      return tags;
    }
    int base = ptags.getXDatadogTagsSize();
    if (calcLLMObsSize(base, tags) <= xDatadogTagsLimit) {
      return tags;
    }

    if (tags.parentAgentName != null) {
      // Measure without the name, then give whatever room is left back to a truncated one.
      LLMObsTagValues idOnly = tags.withAgentAttribution(tags.parentAgentSpanId, null);
      int sizeWithoutName = calcLLMObsSize(base, idOnly);
      if (sizeWithoutName <= xDatadogTagsLimit) {
        int room = xDatadogTagsLimit - sizeWithoutName - PAGENT_NAME_ENTRY_OVERHEAD;
        CharSequence name = tags.parentAgentName.forType(Encoding.DATADOG);
        if (room > 0 && room < name.length()) {
          TagValue truncated = LLMObsTagValues.toTagValue(name.subSequence(0, room));
          if (truncated != null) {
            LLMObsTagValues withTruncatedName =
                tags.withAgentAttribution(tags.parentAgentSpanId, truncated);
            // Encoding the truncated value can cost more than its characters; keep id only then.
            return calcLLMObsSize(base, withTruncatedName) > xDatadogTagsLimit
                ? idOnly
                : withTruncatedName;
          }
        }
        return idOnly;
      }
    }

    // Either there was no name to sacrifice, or the id alone still overflows. Drop attribution
    // rather than lose the whole header.
    return tags.withAgentAttribution(null, null);
  }

  @Override
  protected int appendPrefix(StringBuilder sb, PTags ptags) {
    // Calculate the tag size here and return it. Don't do anything else since there is no prefix.
    return ptags.getXDatadogTagsSize();
  }

  @Override
  protected int appendTag(StringBuilder sb, TagElement key, TagElement value, int size) {
    if (size <= xDatadogTagsLimit) {
      if (sb.length() > 0) {
        sb.append(TAGS_SEPARATOR);
      }
      sb.append(key.forType(Encoding.DATADOG));
      sb.append(TAG_KEY_SEPARATOR);
      sb.append(value.forType(Encoding.DATADOG));
    }
    return size;
  }

  @Override
  protected int appendSuffix(StringBuilder sb, PTags ptags, int size) {
    return size;
  }

  @Override
  protected boolean isTooLarge(StringBuilder sb, int size) {
    return size > xDatadogTagsLimit;
  }

  @Override
  protected boolean isEmpty(StringBuilder sb, int size) {
    return sb.length() == 0;
  }

  private static int validateCharsUntilSeparatorOrEnd(
      String s, int start, char separator, IntPredicate isValid) {
    int end = s.length();
    if (start >= end) {
      return -1;
    }
    int pos = start;
    char c = s.charAt(pos);
    do {
      if (!isValid.test(c) || c == separator) {
        return -1;
      }
      pos++;
      if (pos < end) {
        c = s.charAt(pos);
        if (c == separator) {
          break; // trailing separator allowed; caller resumes parsing from here
        }
      }
    } while (pos < end);

    return pos;
  }

  private static boolean isAllowedKeyChar(int c) {
    // space (MIN_ALLOWED_CHAR) is not allowed
    return c > MIN_ALLOWED_CHAR
        && c <= MAX_ALLOWED_CHAR
        && c != TAG_KEY_SEPARATOR
        && c != TAGS_SEPARATOR;
  }

  private static boolean isAllowedValueChar(int c) {
    return c >= MIN_ALLOWED_CHAR && c <= MAX_ALLOWED_CHAR;
  }
}
