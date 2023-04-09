package datadog.trace.core.propagation.ptags;

import static datadog.trace.api.internal.util.LongStringUtils.toHexStringPadded;

import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.propagation.ptags.PTagsFactory.PTags;
import datadog.trace.core.propagation.ptags.TagElement.Encoding;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class W3CPTagsCodec extends PTagsCodec {
  private static final Logger log = LoggerFactory.getLogger(W3CPTagsCodec.class);

  private static final int MAX_HEADER_SIZE = 256;
  private static final int EMPTY_SIZE = 3; // 'dd='
  private static final char MEMBER_SEPARATOR = ',';
  private static final char ELEMENT_SEPARATOR = ';';
  private static final char KEY_VALUE_SEPARATOR = ':';
  private static final int MIN_ALLOWED_CHAR = 32;
  private static final int MAX_ALLOWED_CHAR = 126;

  @Override
  PropagationTags fromHeaderValue(PTagsFactory tagsFactory, String value) {
    if (value == null || value.isEmpty()) {
      return tagsFactory.empty();
    }

    int len = value.length();
    // Skip over leading whitespace and empty member list elements
    int firstMemberStart = findNextMember(value, 0);
    if (firstMemberStart == len) {
      return tagsFactory.empty();
    }

    // Validate the whole tracestate and figure out where the dd member key and value are located
    int memberStart = firstMemberStart;
    int ddMemberStart = -1; // dd member start position (inclusive)
    int ddMemberValueStart = -1; // dd member value start position (inclusive)
    int ddMemberValueEnd = -1; // dd member value end position including OWS (exclusive)
    int memberIndex = 0;
    int ddMemberIndex = -1;
    while (memberStart < len) {
      if (memberIndex == 32) {
        // TODO should we return one with an error?
        // TODO should we try to pick up the `dd` member anyway?
        return tagsFactory.empty();
      }
      if (ddMemberIndex == -1 && value.startsWith("dd=", memberStart)) {
        ddMemberStart = memberStart;
        ddMemberIndex = memberIndex;
      }
      // Validate the member key
      int pos = validateMemberKey(value, memberStart);
      if (pos < 0) {
        // TODO should we return one with an error?
        return tagsFactory.empty();
      }
      if (ddMemberValueStart == -1 && ddMemberIndex != -1) {
        ddMemberValueStart = pos;
      }
      pos = validateMemberValue(value, pos);
      if (pos < 0) {
        // TODO should we return one with an error?
        return tagsFactory.empty();
      }
      if (ddMemberValueEnd == -1 && ddMemberIndex != -1) {
        ddMemberValueEnd = pos;
      }
      memberStart = findNextMember(value, pos);
      if (memberStart < 0) {
        // TODO should we return one with an error?
        return tagsFactory.empty();
      }
      memberIndex++;
    }

    if (ddMemberIndex == -1) {
      // There was no dd member, so create an empty one with the _suffix_
      return empty(tagsFactory, value);
    }

    List<TagElement> tagPairs = null;
    int tagPos = ddMemberValueStart;
    int samplingPriority = PrioritySampling.UNSET;
    CharSequence origin = null;
    TagValue decisionMakerTagValue = null;
    TagValue traceIdTagValue = null;
    int maxUnknownSize = 0;
    while (tagPos < ddMemberValueEnd) {
      int tagKeyEndsAt =
          validateCharsUntilSeparatorOrEnd(
              value,
              tagPos,
              ddMemberValueEnd,
              KEY_VALUE_SEPARATOR,
              false,
              W3CPTagsCodec::isAllowedKeyChar);
      if (tagKeyEndsAt < 0 || tagKeyEndsAt == ddMemberValueEnd) {
        log.warn("Invalid datadog tags header value: '{}' at {}", value, tagPos);
        // TODO drop parts?
        return empty(tagsFactory, value, firstMemberStart, ddMemberStart, ddMemberValueEnd);
      }
      int tagValuePos = tagKeyEndsAt + 1;
      int tagValueEndsAt =
          validateCharsUntilSeparatorOrEnd(
              value,
              tagValuePos,
              ddMemberValueEnd,
              ELEMENT_SEPARATOR,
              true,
              W3CPTagsCodec::isAllowedValueChar);
      if (tagValueEndsAt < 0) {
        log.warn("Invalid datadog tags header value: '{}' at {}", value, tagKeyEndsAt);
        // TODO drop parts?
        return empty(tagsFactory, value, firstMemberStart, ddMemberStart, ddMemberValueEnd);
      }
      int nextTagPos = tagValueEndsAt + 1;
      if (tagValueEndsAt == ddMemberValueEnd) {
        tagValueEndsAt = stripTrailingOWC(value, tagValuePos, tagValueEndsAt);
      }
      TagKey tagKey = TagKey.from(Encoding.W3C, value, tagPos, tagKeyEndsAt);
      if (tagKey != null) {
        TagValue tagValue = TagValue.from(Encoding.W3C, value, tagValuePos, tagValueEndsAt);
        if (!tagKey.equals(UPSTREAM_SERVICES_DEPRECATED_TAG)) {
          if (!validateTagValue(tagKey, tagValue)) {
            log.warn(
                "Invalid datadog tags header value: '{}' invalid tag value at {}",
                value,
                tagValuePos);
            if (tagKey.equals(TRACE_ID_TAG)) {
              return tagsFactory.createInvalid(PROPAGATION_ERROR_MALFORMED_TID + tagValue);
            }
            // TODO drop parts?
            return empty(tagsFactory, value, firstMemberStart, ddMemberStart, ddMemberValueEnd);
          }
          if (tagKey.equals(DECISION_MAKER_TAG)) {
            decisionMakerTagValue = tagValue;
          } else if (tagKey.equals(TRACE_ID_TAG)) {
            traceIdTagValue = tagValue;
          } else {
            if (tagPairs == null) {
              // This is roughly the size of a two element linked list but can hold six
              tagPairs = new ArrayList<>(6);
            }
            tagPairs.add(tagKey);
            tagPairs.add(tagValue);
          }
        }
      } else {
        // This was not a propagating tag, so check if we know it
        int keyLength = tagKeyEndsAt - tagPos;
        char c = value.charAt(tagPos);
        if (keyLength == 1 && c == 's') {
          samplingPriority = validateSamplingPriority(value, tagValuePos, tagValueEndsAt);
        } else if (keyLength == 1 && c == 'o') {
          origin = TagValue.from(Encoding.W3C, value, tagValuePos, tagValueEndsAt);
        } else {
          if (maxUnknownSize != 0) {
            maxUnknownSize++; // delimiter
          }
          maxUnknownSize += (tagValueEndsAt - tagPos);
        }
      }
      tagPos = nextTagPos;
    }
    return new W3CPTags(
        tagsFactory,
        tagPairs,
        decisionMakerTagValue,
        traceIdTagValue,
        samplingPriority,
        origin,
        value,
        firstMemberStart,
        ddMemberStart,
        ddMemberValueEnd,
        maxUnknownSize);
  }

  @Override
  protected int estimateHeaderSize(PTags pTags) {
    int size = EMPTY_SIZE + 1; // 'dd=' and delimiter;
    // Yes, this is a bit much, but better safe than sorry
    size += pTags.getXDatadogTagsSize();
    if (pTags.getOrigin() != null) {
      size += pTags.getOrigin().length() + 3; // 'o:' + delimiter
    }
    if (pTags.getSamplingPriority() != PrioritySampling.UNSET) {
      size += 5; // 's:-?[0-9]' + delimiter
    }
    if (pTags instanceof W3CPTags) {
      W3CPTags w3CPTags = (W3CPTags) pTags;
      size += w3CPTags.maxUnknownSize;
      if (w3CPTags.ddMemberStart != -1) {
        size += (w3CPTags.original.length() - (w3CPTags.ddMemberValueEnd - w3CPTags.ddMemberStart));
      }
    }
    return size;
  }

  @Override
  protected int appendPrefix(StringBuilder sb, PTags ptags) {
    sb.append("dd=");
    if (ptags.getSamplingPriority() != PrioritySampling.UNSET) {
      sb.append("s:");
      sb.append(ptags.getSamplingPriority());
    }
    CharSequence origin = ptags.getOrigin();
    if (origin != null) {
      if (sb.length() > EMPTY_SIZE) {
        sb.append(';');
      }
      sb.append("o:");
      if (origin instanceof TagValue) {
        sb.append(((TagValue) origin).forType(Encoding.W3C));
      } else {
        sb.append(origin);
      }
    }
    return sb.length();
  }

  @Override
  protected int appendTag(StringBuilder sb, TagElement key, TagElement value, int size) {
    return appendTag(sb, key, value, Encoding.W3C, size);
  }

  @Override
  protected int appendSuffix(StringBuilder sb, PTags ptags, int size) {
    if (size >= MAX_HEADER_SIZE || !(ptags instanceof W3CPTags)) {
      return size;
    }
    W3CPTags w3cPTags = (W3CPTags) ptags;
    size = cleanUpAndAppendUnknown(sb, w3cPTags, size);
    if (size == EMPTY_SIZE) {
      // If we haven't written anything but the 'dd=', then reset the StringBuilder
      sb.setLength(0);
      size = 0;
    }
    // TODO we need to ensure that there are only 32 segments including our own :(
    int newSize = cleanUpAndAppendSuffix(sb, w3cPTags, size);
    if (newSize != size) {
      // We don't care about the total size in bytes here, but only the fact that we added something
      // that should be returned
      size = Math.max(size, EMPTY_SIZE + 1);
    }

    return size;
  }

  @Override
  protected boolean isTooLarge(StringBuilder sb, int size) {
    return size > MAX_HEADER_SIZE;
  }

  @Override
  protected boolean isEmpty(StringBuilder sb, int size) {
    return size <= EMPTY_SIZE;
  }

  private int appendTag(
      StringBuilder sb, TagElement key, TagElement value, Encoding encoding, int size) {
    if (size >= MAX_HEADER_SIZE) {
      return size;
    }
    int validSize = size;
    if (size > EMPTY_SIZE) {
      sb.append(ELEMENT_SEPARATOR);
      size++;
    }
    CharSequence s = key.forType(encoding);
    sb.append(s);
    size += s.length();
    sb.append(KEY_VALUE_SEPARATOR);
    size++;
    s = value.forType(encoding);
    sb.append(s);
    size += s.length();
    if (size > MAX_HEADER_SIZE) {
      sb.setLength(validSize);
      size = validSize;
    }
    return size;
  }

  private static int validateCharsUntilSeparatorOrEnd(
      String s, int start, int end, char separator, boolean allowOWC, IntPredicate isValid) {
    if (start >= end) {
      return -1;
    }
    int pos = start;
    char c = s.charAt(pos);
    boolean definitelyOWC = false;
    do {
      if (allowOWC && isOWC(c)) {
        if (c == '\t') {
          definitelyOWC = true;
        }
      } else {
        if (definitelyOWC || !isValid.test(c) || c == separator) {
          return -1;
        }
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
    // We already know that the segments have been validated against the valid chars for
    // the general tracestate header
    return c > MIN_ALLOWED_CHAR && c <= MAX_ALLOWED_CHAR && c != KEY_VALUE_SEPARATOR;
  }

  private static boolean isAllowedValueChar(int c) {
    // We already know that the segments have been validated against the valid chars for
    // the general tracestate header
    return c >= MIN_ALLOWED_CHAR && c <= MAX_ALLOWED_CHAR;
  }

  private static int validateSamplingPriority(String original, int start, int end) {
    try {
      return parseIntDecimal(original, start, end);
    } catch (Exception ignored) {
      return PrioritySampling.UNSET;
    }
  }

  // Integer.parseInt(s, start, end, radix) is only available from Java9+, so do it by hand
  private static int parseIntDecimal(String original, int start, int end)
      throws NumberFormatException {
    if (start < 0 || start > end || end > original.length()) {
      throw new IndexOutOfBoundsException();
    }

    boolean negative = false;
    int i = start;
    int limit = -Integer.MAX_VALUE;

    if (i < end) {
      int firstChar = original.charAt(i);
      if (firstChar < '0') {
        if (firstChar == '-') {
          negative = true;
          limit = Integer.MIN_VALUE;
        } else if (firstChar != '+') {
          throw new NumberFormatException(original);
        }
        i++;
        if (i == end) {
          // There needs to be something after the +/-
          throw new NumberFormatException(original);
        }
      }
      int multmin = limit / 10;
      int result = 0;
      while (i < end) {
        // Accumulating negatively avoids surprises near MAX_VALUE
        int digit = original.charAt(i) - '0';
        if (digit < 0 || digit > 9 || result < multmin) {
          throw new NumberFormatException(original);
        }
        result *= 10;
        if (result < limit + digit) {
          throw new NumberFormatException(original);
        }
        i++;
        result -= digit;
      }
      return negative ? result : -result;
    } else {
      throw new NumberFormatException("");
    }
  }

  // This is The Fine Specification for the member key https://www.w3.org/TR/trace-context/#key
  private static int validateMemberKey(String original, int start) {
    int end = original.length();
    if (start < 0 || start >= end) {
      return -1;
    }
    boolean multi = false;
    int length = 1;
    int pos = start;
    for (; pos < end; pos++, length++) {
      if (length > 242) {
        if (multi) {
          // We're beyond the length of the allowed tenant id and @ delimiter
          return -1;
        } else if (length > 257) {
          // We're beyond the length of the allowed simple key and = delimiter
          return -1;
        }
      }
      char c = original.charAt(pos);
      if (isLcAlpha(c)) {
        continue;
      }
      if (isDigit(c)) {
        if (length == 1) {
          multi = true;
        }
        continue;
      }
      if (length == 1) {
        // The member key can only start with lower case alpha or a number
        return -1;
      }
      if (isValidExtra(c)) {
        continue;
      }
      if (c == '=') {
        if (multi) {
          // If the member key started with a number then it's a multi tenant key and must have an @
          return -1;
        } else {
          pos++;
          break;
        }
      }
      if (c == '@') {
        if (length > 242) {
          // We're beyond the length of the allowed tenant id and @ delimiter
          return -1;
        }
        multi = true;
        pos++;
        break;
      }
      return -1;
    }

    if (multi) {
      // Validate the multi tenant system id part
      length = 1;
      for (; pos < end; pos++, length++) {
        if (length > 15) {
          // We're beyond the length of the allowed system id and = delimiter
          return -1;
        }
        char c = original.charAt(pos);
        if (isLcAlpha(c)) {
          continue;
        }
        if (length == 1) {
          // The system id can only start with lower case alpha
          return -1;
        }
        if (isDigit(c) || isValidExtra(c)) {
          continue;
        }
        if (c == '=') {
          pos++;
          break;
        }
      }
    }

    if (pos >= end) {
      // There needs to be something after the equals sign
      return -1;
    }

    return pos;
  }

  // This is The Fine Specification for the member value https://www.w3.org/TR/trace-context/#value
  // Please note the wonderful decision to allow ' ' in the value but not let it end with ' ', since
  // that is indistinguishable from optional whitespace at the end
  private static int validateMemberValue(String original, int start) {
    int end = original.length();
    if (start < 0 || start >= end) {
      return -1;
    }
    int length = 1;
    int nonOWSLength = 0;
    int pos = start;
    boolean inWhiteSpace = false;
    boolean onlyWhiteSpace = true;
    boolean moreNonWSAllowed = true;
    for (; pos < end; pos++, length++) {
      if (!inWhiteSpace) {
        nonOWSLength = length - 1;
      }
      if (nonOWSLength > 256) {
        // We're beyond the length of the allowed member value and ',' delimiter
        return -1;
      }
      char c = original.charAt(pos);
      if (c == ' ') {
        inWhiteSpace = true;
        continue;
      }
      if (c == '\t') {
        inWhiteSpace = true;
        // If we encounter a `\t` then we can't have more normal characters after
        moreNonWSAllowed = false;
        continue;
      }
      if (c == ',') {
        break;
      }
      if (!moreNonWSAllowed) {
        return -1;
      }
      inWhiteSpace = false;
      if (isValidMemberValueChar(c)) {
        onlyWhiteSpace = false;
        continue;
      }
      return -1;
    }
    if (onlyWhiteSpace) {
      return -1;
    }
    if (!inWhiteSpace && length > 257) {
      // We're beyond the length of the allowed member value and ',' delimiter
      return -1;
    }

    return pos;
  }

  private static int findNextMember(String original, int start) {
    int len = original.length();
    if (start < 0) {
      return -1;
    }
    if (start >= len) {
      return len;
    }

    int pos = start;
    for (; pos < len; pos++) {
      char c = original.charAt(pos);
      if (isOWC(c) || c == ',') {
        continue;
      }
      break;
    }
    return pos;
  }

  private static boolean isLcAlpha(char c) {
    return c >= 'a' && c <= 'z';
  }

  private static boolean isValidExtra(char c) {
    return c == '_' || c == '-' || c == '*' || c == '/';
  }

  private static boolean isValidMemberValueChar(char c) {
    return c >= ' ' && c <= '~' && c != ',' && c != '=';
  }

  private static boolean isOWC(char c) {
    return c == ' ' || c == '\t';
  }

  private static int stripTrailingOWC(String original, int start, int end) {
    char c = original.charAt(--end);
    while (isOWC(c) && end > start) {
      c = original.charAt(--end);
    }
    return ++end;
  }

  private static int cleanUpAndAppendUnknown(StringBuilder sb, W3CPTags w3CPTags, int size) {
    if (w3CPTags.maxUnknownSize == 0
        || w3CPTags.ddMemberStart == -1
        || w3CPTags.ddMemberStart >= w3CPTags.ddMemberValueEnd) {
      return size;
    }
    String original = w3CPTags.original;
    int elementStart = w3CPTags.ddMemberStart + EMPTY_SIZE; // skip over 'dd='
    int okSize = size;
    while (elementStart < w3CPTags.ddMemberValueEnd && size < MAX_HEADER_SIZE) {
      okSize = size;
      int elementEnd = original.indexOf(ELEMENT_SEPARATOR, elementStart);
      if (elementEnd < 0) {
        elementEnd = w3CPTags.ddMemberValueEnd;
      }
      if (!original.startsWith(Encoding.W3C.getPrefix(), elementStart)) {
        char first = original.charAt(elementStart);
        char second = original.charAt(elementStart + 1);
        if (second != KEY_VALUE_SEPARATOR || (first != 'o' && first != 's')) {
          // only append elements that we don't know about or are not tags
          if (sb.length() > EMPTY_SIZE) {
            sb.append(ELEMENT_SEPARATOR);
            size++;
          }
          int end = elementEnd;
          if (end == w3CPTags.ddMemberValueEnd) {
            end = stripTrailingOWC(original, elementStart, end);
          }
          sb.append(original, elementStart, end);
          size += (end - elementStart);
        }
      }
      elementStart = elementEnd + 1;
    }
    if (size > MAX_HEADER_SIZE) {
      sb.setLength(okSize);
      size = okSize;
    }
    return size;
  }

  private static int cleanUpAndAppendSuffix(StringBuilder sb, W3CPTags w3CPTags, int size) {
    String original = w3CPTags.original;
    int len = original.length();
    int memberStart = findNextMember(original, 0);
    while (memberStart < len) {
      int memberEnd = original.indexOf(MEMBER_SEPARATOR, memberStart);
      if (memberEnd < 0) {
        memberEnd = len;
      }
      if (memberStart != w3CPTags.ddMemberStart) {
        if (sb.length() > 0) {
          sb.append(MEMBER_SEPARATOR);
          size++;
        }
        int end = stripTrailingOWC(original, memberStart, memberEnd);
        sb.append(original, memberStart, end);
        size += (end - memberStart);
      }
      memberStart = findNextMember(original, memberEnd + 1);
    }
    return size;
  }

  private static W3CPTags empty(PTagsFactory factory, String original) {
    return empty(factory, original, 0, -1, -1);
  }

  private static W3CPTags empty(
      PTagsFactory factory,
      String original,
      int firstMemberStart,
      int ddMemberStart,
      int ddMemberValueEnd) {
    return new W3CPTags(
        factory,
        null,
        null,
        null,
        PrioritySampling.UNSET,
        null,
        original,
        firstMemberStart,
        ddMemberStart,
        ddMemberValueEnd,
        0);
  }

  private static class W3CPTags extends PTags {
    private final String original;
    private final int firstMemberStart;
    private final int ddMemberStart;
    private final int ddMemberValueEnd;
    private final int maxUnknownSize;
    private String error;

    public W3CPTags(
        PTagsFactory factory,
        List<TagElement> tagPairs,
        TagValue decisionMakerTagValue,
        TagValue traceIdTagValue,
        int samplingPriority,
        CharSequence origin,
        String original,
        int firstMemberStart,
        int ddMemberStart,
        int ddMemberValueEnd,
        int maxUnknownSize) {
      super(factory, tagPairs, decisionMakerTagValue, traceIdTagValue, samplingPriority, origin);
      this.original = original;
      this.firstMemberStart = firstMemberStart;
      this.ddMemberStart = ddMemberStart;
      this.ddMemberValueEnd = ddMemberValueEnd;
      this.maxUnknownSize = maxUnknownSize;
      this.error = null;
    }

    @Override
    String getError() {
      if (this.error != null) {
        return this.error;
      }
      return super.getError();
    }

    @Override
    public void updateTraceIdHighOrderBits(long highOrderBits) {
      long currentHighOrderBits = getTraceIdHighOrderBits();
      // If defined from parsing but different from expected value, mark as decoding error
      if (currentHighOrderBits != 0 && currentHighOrderBits != highOrderBits) {
        this.error =
            PROPAGATION_ERROR_INCONSISTENT_TID + toHexStringPadded(currentHighOrderBits, 16);
      }
      super.updateTraceIdHighOrderBits(highOrderBits);
    }
  }
}
