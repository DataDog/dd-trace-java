package datadog.trace.core.propagation.ptags;

final class OtelTraceState {
  static final int MAX_VALUE_LENGTH = 256;

  private static final int HEX_DIGITS = 14;
  private static final long KNUTH_FACTOR = 1111111111111111111L;
  private static final long MAX_THRESHOLD = (1L << 56) - 1;
  private static final double THRESHOLD_RANGE = 1L << 56;
  private static final long NO_VALUE = -1;
  private static final int FLAGS_HAS_MULTIPLE_RANDOM_VALUES = 1;
  private static final int FLAGS_HAS_LOCALLY_GENERATED_RANDOM_VALUE = 1 << 1;
  private static final String RANDOM_VALUE_KEY = "rv:";
  private static final String THRESHOLD_KEY = "th:";
  private static final int DEFAULT_VALUE_CAPACITY =
      RANDOM_VALUE_KEY.length() + HEX_DIGITS + 1 + THRESHOLD_KEY.length() + HEX_DIGITS;

  private final String value;
  private final long randomValue;
  private final long threshold;
  private final int originalPosition;
  private final int originalSize;
  // Combination of FLAGS_HAS_MULTIPLE_RANDOM_VALUES and FLAGS_HAS_LOCALLY_GENERATED_RANDOM_VALUE.
  private final int flags;

  private OtelTraceState(
      String value,
      long randomValue,
      long threshold,
      int originalPosition,
      int originalSize,
      int flags) {
    this.value = value;
    this.randomValue = randomValue;
    this.threshold = threshold;
    this.originalPosition = originalPosition;
    this.originalSize = originalSize;
    this.flags = flags;
  }

  static OtelTraceState parse(String raw, int originalPosition, int originalSize) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }
    long randomValue = NO_VALUE;
    long threshold = NO_VALUE;
    int flags = 0;
    StringBuilder normalized = null;
    int start = 0;
    // Iterate over semicolon-delimited OTel tracestate fields, including trailing empty fields.
    while (start <= raw.length()) {
      int end = raw.indexOf(';', start);
      if (end < 0) {
        end = raw.length();
      }
      int separator = raw.indexOf(':', start);
      if (separator >= end) {
        separator = -1;
      }
      int fieldValueStart = separator < 0 ? end : separator + 1;
      boolean validField;
      if (hasKey(raw, start, end, separator, 'r', 'v')) {
        boolean validRandomValueLength = end - fieldValueStart == HEX_DIGITS;
        long parsedRandomValue =
            validRandomValueLength ? parseLowercaseHex(raw, fieldValueStart, end) : NO_VALUE;
        if (parsedRandomValue != NO_VALUE) {
          if (randomValue == NO_VALUE) {
            randomValue = parsedRandomValue;
          } else {
            flags |= FLAGS_HAS_MULTIPLE_RANDOM_VALUES;
          }
          validField = true;
        } else {
          validField = false;
        }
      } else if (hasKey(raw, start, end, separator, 't', 'h')) {
        boolean validThresholdLength = fieldValueStart < end && end - fieldValueStart <= HEX_DIGITS;
        long parsedThreshold =
            validThresholdLength ? parseLowercaseHex(raw, fieldValueStart, end) : NO_VALUE;
        if (parsedThreshold != NO_VALUE) {
          if (threshold == NO_VALUE) {
            threshold = parsedThreshold;
          }
          validField = true;
        } else {
          validField = false;
        }
      } else {
        validField = start < end;
      }
      if (validField) {
        if (normalized != null) {
          int separatorSize = normalized.length() == 0 ? 0 : 1;
          int fieldLength = end - start;
          if (normalized.length() + separatorSize + fieldLength <= MAX_VALUE_LENGTH) {
            if (separatorSize != 0) {
              normalized.append(';');
            }
            normalized.append(raw, start, end);
          }
        }
      } else {
        normalized = startNormalizing(raw, normalized, start);
      }
      start = end + 1;
    }

    String value = normalized == null ? raw : normalized.toString();
    if (value.isEmpty()) {
      return null;
    }
    return new OtelTraceState(
        value,
        randomValue,
        threshold,
        normalized == null ? originalPosition : 0,
        originalSize,
        flags);
  }

  static OtelTraceState updateProbability(
      OtelTraceState current,
      long traceIdLowOrderBits,
      double sampleRate,
      boolean sampled,
      int samplingPriority) {
    String currentValue = current == null ? null : current.value;
    int originalSize = current == null ? 0 : current.originalSize;

    // `sampled` is the raw probability result; `samplingPriority` may be changed by rate limiting.
    boolean limiterDemoted = sampled && samplingPriority <= 0;
    if (limiterDemoted) {
      if (current != null) {
        return current.removeThresholdForLimiterDemotion();
      }
      return null;
    }

    long threshold = computeThreshold(sampleRate);
    long randomValue = computeRandomValue(traceIdLowOrderBits);
    if (sampled && randomValue < threshold) {
      randomValue = threshold;
    } else if (!sampled && randomValue >= threshold) {
      randomValue = threshold == 0 ? 0 : threshold - 1;
    }

    return create(randomValue, threshold, currentValue, originalSize, true);
  }

  OtelTraceState removeForNonProbabilityDecision() {
    if (!hasLocallyGeneratedRandomValue() && threshold == NO_VALUE && !hasMultipleRandomValues()) {
      return this;
    }
    long retainedRandomValue = hasLocallyGeneratedRandomValue() ? NO_VALUE : randomValue;
    return create(retainedRandomValue, NO_VALUE, value, originalSize, false);
  }

  OtelTraceState removeThresholdForLimiterDemotion() {
    if (threshold == NO_VALUE) {
      return this;
    }
    return create(
        randomValue,
        NO_VALUE,
        value,
        originalSize,
        hasLocallyGeneratedRandomValue());
  }

  String getValue() {
    return value;
  }

  int length() {
    return value.length();
  }

  int getOriginalPosition() {
    return originalPosition;
  }

  int getOriginalSize() {
    return originalSize;
  }

  private static OtelTraceState create(
      long randomValue,
      long threshold,
      String existingValue,
      int originalSize,
      boolean locallyGeneratedRandomValue) {
    StringBuilder value = new StringBuilder(DEFAULT_VALUE_CAPACITY);
    if (randomValue != NO_VALUE) {
      value.append(RANDOM_VALUE_KEY);
      appendHex(value, randomValue, HEX_DIGITS);
    }
    if (threshold != NO_VALUE) {
      if (value.length() > 0) {
        value.append(';');
      }
      value.append(THRESHOLD_KEY);
      appendHex(value, threshold, thresholdHexDigits(threshold));
    }
    if (existingValue != null) {
      appendUnknownFields(value, existingValue);
    }
    if (value.length() == 0) {
      return null;
    }
    return new OtelTraceState(
        value.toString(),
        randomValue,
        threshold,
        0,
        originalSize,
        locallyGeneratedRandomValue ? FLAGS_HAS_LOCALLY_GENERATED_RANDOM_VALUE : 0);
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

  /** Appends fields other than {@code rv} and {@code th} from the existing value. */
  private static void appendUnknownFields(StringBuilder value, String existingValue) {
    int start = 0;
    while (start < existingValue.length()) {
      int end = existingValue.indexOf(';', start);
      if (end < 0) {
        end = existingValue.length();
      }
      int separator = existingValue.indexOf(':', start);
      if (separator >= end) {
        separator = -1;
      }
      if (!hasKey(existingValue, start, end, separator, 'r', 'v')
          && !hasKey(existingValue, start, end, separator, 't', 'h')) {
        int separatorSize = value.length() == 0 ? 0 : 1;
        int fieldLength = end - start;
        if (value.length() + separatorSize + fieldLength <= MAX_VALUE_LENGTH) {
          if (separatorSize != 0) {
            value.append(';');
          }
          value.append(existingValue, start, end);
        }
      }
      start = end + 1;
    }
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
    return (flags & FLAGS_HAS_MULTIPLE_RANDOM_VALUES) != 0;
  }

  private boolean hasLocallyGeneratedRandomValue() {
    return (flags & FLAGS_HAS_LOCALLY_GENERATED_RANDOM_VALUE) != 0;
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
