package datadog.trace.core.propagation.ptags;

import datadog.trace.api.ProductTraceSource;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.propagation.ptags.PTagsFactory.PTags;
import datadog.trace.core.propagation.ptags.TagElement.Encoding;
import datadog.trace.relocate.api.RatelimitedLogger;
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
    return tagsFactory.createValid(tagPairs, decisionMakerTagValue, traceIdTagValue, traceSource);
  }

  @Override
  protected int estimateHeaderSize(PTags pTags) {
    return pTags.getXDatadogTagsSize();
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
        // It's not allowed to have the separator as the last character so only check
        // if there is something after the separator
        if (pos < end - 1 && c == separator) {
          break;
        }
      }
    } while (pos < end);

    return pos;
  }

  private static boolean isAllowedKeyChar(int c) {
    // space (MIN_ALLOWED_CHAR) is not allowed
    return c > MIN_ALLOWED_CHAR && c <= MAX_ALLOWED_CHAR && c != TAGS_SEPARATOR;
  }

  private static boolean isAllowedValueChar(int c) {
    return c >= MIN_ALLOWED_CHAR && c <= MAX_ALLOWED_CHAR;
  }
}
