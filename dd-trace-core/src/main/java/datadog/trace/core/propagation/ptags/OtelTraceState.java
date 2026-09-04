package datadog.trace.core.propagation.ptags;

final class OtelTraceState {
  static final int MAX_VALUE_LENGTH = 256;

  private static final int HEX_DIGITS = 14;
  private static final long KNUTH_FACTOR = 1111111111111111111L;
  private static final long MAX_THRESHOLD = (1L << 56) - 1;
  private static final double THRESHOLD_RANGE = 1L << 56;
  private static final long NO_VALUE = -1;
  private static final int HAS_MULTIPLE_RANDOM_VALUES = 1;
  private static final int HAS_LOCALLY_GENERATED_RANDOM_VALUE = 1 << 1;
  private static final String RANDOM_VALUE_KEY = "rv:";
  private static final String THRESHOLD_KEY = "th:";
  private static final int DEFAULT_VALUE_CAPACITY =
      RANDOM_VALUE_KEY.length() + HEX_DIGITS + 1 + THRESHOLD_KEY.length() + HEX_DIGITS;

  private final String value;
  private final long randomValue;
  private final long threshold;
  private final int inheritedPosition;
  private final int flags;

  private OtelTraceState(
      String value, long randomValue, long threshold, int inheritedPosition, int flags) {
    this.value = value;
    this.randomValue = randomValue;
    this.threshold = threshold;
    this.inheritedPosition = inheritedPosition;
    this.flags = flags;
  }

  static OtelTraceState parse(String raw, int inheritedPosition) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }

    long randomValue = NO_VALUE;
    long threshold = NO_VALUE;
    int flags = 0;
    StringBuilder normalized = null;
    int start = 0;
    while (start < raw.length()) {
      int end = raw.indexOf(';', start);
      if (end < 0) {
        end = raw.length();
      }
      int separator = raw.indexOf(':', start);
      if (separator >= end) {
        separator = -1;
      }
      int fieldValueStart = separator < 0 ? end : separator + 1;
      if (hasKey(raw, start, end, separator, 'r', 'v')) {
        long parsedRandomValue =
            end - fieldValueStart == HEX_DIGITS
                ? parseLowercaseHex(raw, fieldValueStart, end)
                : NO_VALUE;
        if (parsedRandomValue != NO_VALUE) {
          if (randomValue == NO_VALUE) {
            randomValue = parsedRandomValue;
          } else {
            flags |= HAS_MULTIPLE_RANDOM_VALUES;
          }
          if (normalized != null) {
            appendField(normalized, raw, start, end);
          }
        } else {
          normalized = startNormalizing(raw, normalized, start);
        }
      } else if (hasKey(raw, start, end, separator, 't', 'h')) {
        long parsedThreshold =
            fieldValueStart < end && end - fieldValueStart <= HEX_DIGITS
                ? parseLowercaseHex(raw, fieldValueStart, end)
                : NO_VALUE;
        if (parsedThreshold != NO_VALUE) {
          if (threshold == NO_VALUE) {
            threshold = parsedThreshold;
          }
          if (normalized != null) {
            appendField(normalized, raw, start, end);
          }
        } else {
          normalized = startNormalizing(raw, normalized, start);
        }
      } else if (start < end) {
        if (normalized != null) {
          appendField(normalized, raw, start, end);
        }
      } else {
        normalized = startNormalizing(raw, normalized, start);
      }
      start = end + 1;
    }

    if (raw.charAt(raw.length() - 1) == ';') {
      normalized = startNormalizing(raw, normalized, raw.length());
    }

    String value = normalized == null ? raw : normalized.toString();
    if (value.isEmpty()) {
      return null;
    }
    return new OtelTraceState(
        value, randomValue, threshold, normalized == null ? inheritedPosition : 0, flags);
  }

  static OtelTraceState updateProbability(
      OtelTraceState current,
      long traceIdLowOrderBits,
      double sampleRate,
      boolean sampled,
      int samplingPriority) {
    String currentValue = current == null ? null : current.value;

    // `sampled` is the raw probability result; `samplingPriority` may be changed by rate limiting.
    if (sampled && samplingPriority <= 0) {
      if (current != null) {
        return current.removeThresholdForLimiterDemotion();
      }
      return create(computeRandomValue(traceIdLowOrderBits), NO_VALUE, currentValue, true);
    }

    long threshold = computeThreshold(sampleRate);
    long randomValue = computeRandomValue(traceIdLowOrderBits);
    if (sampled && randomValue < threshold) {
      randomValue = threshold;
    } else if (!sampled && randomValue >= threshold) {
      randomValue = threshold == 0 ? 0 : threshold - 1;
    }

    return create(randomValue, threshold, currentValue, true);
  }

  OtelTraceState removeForNonProbabilityDecision() {
    if (!hasLocallyGeneratedRandomValue() && threshold == NO_VALUE && !hasMultipleRandomValues()) {
      return this;
    }
    long retainedRandomValue = hasLocallyGeneratedRandomValue() ? NO_VALUE : randomValue;
    return create(retainedRandomValue, NO_VALUE, value, false);
  }

  OtelTraceState removeThresholdForLimiterDemotion() {
    if (threshold == NO_VALUE) {
      return this;
    }
    return create(randomValue, NO_VALUE, value, hasLocallyGeneratedRandomValue());
  }

  String getValue() {
    return value;
  }

  int length() {
    return value.length();
  }

  int getInheritedPosition() {
    return inheritedPosition;
  }

  private static OtelTraceState create(
      long randomValue, long threshold, String previousValue, boolean locallyGeneratedRandomValue) {
    StringBuilder value = new StringBuilder(DEFAULT_VALUE_CAPACITY);
    if (randomValue != NO_VALUE) {
      appendRandomValue(value, randomValue);
    }
    if (threshold != NO_VALUE) {
      appendThreshold(value, threshold);
    }
    if (previousValue != null) {
      appendUnknownFields(value, previousValue);
    }
    if (value.length() == 0) {
      return null;
    }
    return new OtelTraceState(
        value.toString(),
        randomValue,
        threshold,
        0,
        locallyGeneratedRandomValue ? HAS_LOCALLY_GENERATED_RANDOM_VALUE : 0);
  }

  private static StringBuilder startNormalizing(String raw, StringBuilder normalized, int start) {
    if (normalized != null) {
      return normalized;
    }
    normalized = new StringBuilder(raw.length());
    if (start > 0) {
      normalized.append(raw, 0, start - 1);
    }
    return normalized;
  }

  private static void appendRandomValue(StringBuilder value, long randomValue) {
    if (appendFieldPrefix(value, RANDOM_VALUE_KEY.length() + HEX_DIGITS)) {
      value.append(RANDOM_VALUE_KEY);
      appendHex(value, randomValue, HEX_DIGITS);
    }
  }

  private static void appendThreshold(StringBuilder value, long threshold) {
    int hexDigits = thresholdHexDigits(threshold);
    if (appendFieldPrefix(value, THRESHOLD_KEY.length() + hexDigits)) {
      value.append(THRESHOLD_KEY);
      appendHex(value, threshold, hexDigits);
    }
  }

  private static void appendUnknownFields(StringBuilder value, String previousValue) {
    int start = 0;
    while (start < previousValue.length()) {
      int end = previousValue.indexOf(';', start);
      if (end < 0) {
        end = previousValue.length();
      }
      int separator = previousValue.indexOf(':', start);
      if (separator >= end) {
        separator = -1;
      }
      if (!hasKey(previousValue, start, end, separator, 'r', 'v')
          && !hasKey(previousValue, start, end, separator, 't', 'h')) {
        appendField(value, previousValue, start, end);
      }
      start = end + 1;
    }
  }

  private static void appendField(StringBuilder value, String field, int start, int end) {
    if (!appendFieldPrefix(value, end - start)) {
      return;
    }
    value.append(field, start, end);
  }

  private static boolean appendFieldPrefix(StringBuilder value, int fieldLength) {
    int separatorSize = value.length() == 0 ? 0 : 1;
    if (value.length() + separatorSize + fieldLength > MAX_VALUE_LENGTH) {
      return false;
    }
    if (separatorSize != 0) {
      value.append(';');
    }
    return true;
  }

  private static boolean hasKey(
      String value, int start, int end, int separator, char first, char second) {
    if (separator >= 0) {
      return separator == start + 2
          && value.charAt(start) == first
          && value.charAt(start + 1) == second;
    }
    return end == start + 2 && value.charAt(start) == first && value.charAt(start + 1) == second;
  }

  private static long parseLowercaseHex(String value, int start, int end) {
    long parsed = 0;
    for (int i = start; i < end; i++) {
      char character = value.charAt(i);
      if (character >= '0' && character <= '9') {
        parsed = (parsed << 4) | character - '0';
      } else if (character >= 'a' && character <= 'f') {
        parsed = (parsed << 4) | character - 'a' + 10;
      } else {
        return NO_VALUE;
      }
    }
    return parsed;
  }

  private boolean hasMultipleRandomValues() {
    return (flags & HAS_MULTIPLE_RANDOM_VALUES) != 0;
  }

  private boolean hasLocallyGeneratedRandomValue() {
    return (flags & HAS_LOCALLY_GENERATED_RANDOM_VALUE) != 0;
  }

  private static void appendHex(StringBuilder value, long number, int digits) {
    for (int shift = (HEX_DIGITS - 1) * 4; shift >= (HEX_DIGITS - digits) * 4; shift -= 4) {
      value.append(Character.forDigit((int) (number >>> shift) & 0xF, 16));
    }
  }

  private static long computeRandomValue(long traceIdLowOrderBits) {
    return (~(traceIdLowOrderBits * KNUTH_FACTOR)) >>> 8;
  }

  private static long computeThreshold(double sampleRate) {
    long threshold = Math.round((1 - sampleRate) * THRESHOLD_RANGE);
    return Math.max(0, Math.min(threshold, MAX_THRESHOLD));
  }

  private static int thresholdHexDigits(long threshold) {
    int digits = HEX_DIGITS;
    while (digits > 1 && (threshold & 0xF) == 0) {
      digits--;
      threshold >>>= 4;
    }
    return digits;
  }
}
