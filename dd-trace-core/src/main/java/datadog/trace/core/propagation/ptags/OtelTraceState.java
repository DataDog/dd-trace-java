package datadog.trace.core.propagation.ptags;

final class OtelTraceState implements CharSequence {
  private static final String RANDOM_VALUE_KEY = "rv:";
  private static final String THRESHOLD_KEY = "th:";
  private static final long HASH_MULTIPLIER = 1_111_111_111_111_111_111L;
  private static final long MAX_56_BIT_VALUE = 0x00ff_ffff_ffff_ffffL;
  private static final double TWO_TO_56 = 0x1.0p56;
  private static final int MAX_VALUE_LENGTH = 256;

  private final CharSequence value;
  private final CharSequence fields;
  private final int originalSize;
  private final long randomValue;
  private final long threshold;
  private final int randomValueStart;
  private final int randomValueEnd;
  private final int thresholdStart;
  private final int thresholdEnd;
  private final boolean includeRandomValue;
  private final boolean includeThreshold;
  private final boolean inheritedRandomValue;
  private volatile String materializedValue;

  private OtelTraceState(
      CharSequence value,
      CharSequence fields,
      int originalSize,
      long randomValue,
      long threshold,
      int randomValueStart,
      int randomValueEnd,
      int thresholdStart,
      int thresholdEnd,
      boolean includeRandomValue,
      boolean includeThreshold,
      boolean inheritedRandomValue) {
    this.value = value;
    this.fields = fields;
    this.originalSize = originalSize;
    this.randomValue = randomValue;
    this.threshold = threshold;
    this.randomValueStart = randomValueStart;
    this.randomValueEnd = randomValueEnd;
    this.thresholdStart = thresholdStart;
    this.thresholdEnd = thresholdEnd;
    this.includeRandomValue = includeRandomValue;
    this.includeThreshold = includeThreshold;
    this.inheritedRandomValue = inheritedRandomValue;
  }

  static OtelTraceState parse(CharSequence raw, int originalSize) {
    if (raw == null || raw.length() == 0 || raw.length() > MAX_VALUE_LENGTH) {
      return null;
    }

    int randomValueStart = -1;
    int randomValueEnd = -1;
    int thresholdStart = -1;
    int thresholdEnd = -1;
    boolean randomValueSeen = false;
    boolean invalidRandomValue = false;
    boolean hasUnknownField = false;
    boolean normalized = false;
    int start = 0;
    while (start <= raw.length()) {
      int end = indexOf(raw, ';', start);
      if (end < 0) {
        end = raw.length();
      }
      if (startsWith(raw, start, end, RANDOM_VALUE_KEY)) {
        int candidateStart = start + RANDOM_VALUE_KEY.length();
        if (!randomValueSeen) {
          randomValueSeen = true;
          if (isLowerHex(raw, candidateStart, end, 14, 14)) {
            randomValueStart = candidateStart;
            randomValueEnd = end;
          } else {
            invalidRandomValue = true;
            normalized = true;
          }
        } else {
          normalized = true;
        }
      } else if (startsWith(raw, start, end, THRESHOLD_KEY)) {
        int candidateStart = start + THRESHOLD_KEY.length();
        if (thresholdStart < 0 && isLowerHex(raw, candidateStart, end, 1, 14)) {
          thresholdStart = candidateStart;
          thresholdEnd = end;
        } else {
          normalized = true;
        }
      } else if (isUnknownField(raw, start, end)) {
        hasUnknownField = true;
      } else {
        normalized = true;
      }
      if (end == raw.length()) {
        break;
      }
      start = end + 1;
    }

    if (invalidRandomValue) {
      thresholdStart = -1;
      thresholdEnd = -1;
    }

    if (randomValueStart < 0 && thresholdStart < 0 && !hasUnknownField) {
      return null;
    }
    if (normalized) {
      CharSequence normalizedValue =
          normalize(raw, randomValueStart, randomValueEnd, thresholdStart, thresholdEnd);
      return parseCanonical(normalizedValue, originalSize);
    }
    return new OtelTraceState(
        raw,
        raw,
        originalSize,
        parseHex(raw, randomValueStart, randomValueEnd),
        parseThreshold(raw, thresholdStart, thresholdEnd),
        randomValueStart,
        randomValueEnd,
        thresholdStart,
        thresholdEnd,
        randomValueStart >= 0,
        thresholdStart >= 0,
        true);
  }

  private static OtelTraceState parseCanonical(CharSequence value, int originalSize) {
    int randomValueStart = -1;
    int randomValueEnd = -1;
    int thresholdStart = -1;
    int thresholdEnd = -1;
    int start = 0;
    while (start < value.length()) {
      int end = indexOf(value, ';', start);
      if (end < 0) {
        end = value.length();
      }
      if (startsWith(value, start, end, RANDOM_VALUE_KEY)) {
        randomValueStart = start + RANDOM_VALUE_KEY.length();
        randomValueEnd = end;
      } else if (startsWith(value, start, end, THRESHOLD_KEY)) {
        thresholdStart = start + THRESHOLD_KEY.length();
        thresholdEnd = end;
      }
      start = end + 1;
    }
    return new OtelTraceState(
        value,
        value,
        originalSize,
        parseHex(value, randomValueStart, randomValueEnd),
        parseThreshold(value, thresholdStart, thresholdEnd),
        randomValueStart,
        randomValueEnd,
        thresholdStart,
        thresholdEnd,
        randomValueStart >= 0,
        thresholdStart >= 0,
        true);
  }

  static OtelTraceState fromProbabilityDecision(
      long traceIdLowOrderBits, double rate, int samplingPriority) {
    long hash = traceIdLowOrderBits * HASH_MULTIPLIER;
    long randomValue = (~hash) >>> 8;
    long threshold = Math.round((1.0 - rate) * TWO_TO_56);
    if (threshold > MAX_56_BIT_VALUE) {
      threshold = MAX_56_BIT_VALUE;
    }
    if (samplingPriority > 0 && randomValue < threshold) {
      randomValue = threshold;
    } else if (samplingPriority <= 0 && randomValue >= threshold) {
      randomValue = threshold == 0 ? 0 : threshold - 1;
    }

    return new OtelTraceState(
        null, null, 0, randomValue, threshold, -1, -1, -1, -1, true, true, false);
  }

  OtelTraceState withoutThreshold() {
    if (!includeThreshold) {
      return this;
    }
    return withFields(includeRandomValue, false, inheritedRandomValue);
  }

  OtelTraceState forNonProbabilityDecision() {
    return withFields(inheritedRandomValue && includeRandomValue, false, inheritedRandomValue);
  }

  boolean isConsistentWith(boolean sampled) {
    if (!includeRandomValue || !includeThreshold) {
      return true;
    }
    return (randomValue >= threshold) == sampled;
  }

  private OtelTraceState withFields(
      boolean retainRandomValue, boolean retainThreshold, boolean randomValueIsInherited) {
    if (!retainRandomValue && !retainThreshold && !hasUnknownFields()) {
      return null;
    }
    return new OtelTraceState(
        null,
        fields,
        0,
        randomValue,
        threshold,
        randomValueStart,
        randomValueEnd,
        thresholdStart,
        thresholdEnd,
        retainRandomValue,
        retainThreshold,
        randomValueIsInherited);
  }

  int getOriginalSize() {
    return originalSize;
  }

  boolean isMaterialized() {
    return materializedValue != null;
  }

  @Override
  public int length() {
    return value == null ? materialize().length() : value.length();
  }

  @Override
  public char charAt(int index) {
    return value == null ? materialize().charAt(index) : value.charAt(index);
  }

  @Override
  public CharSequence subSequence(int start, int end) {
    return value == null ? materialize().subSequence(start, end) : value.subSequence(start, end);
  }

  @Override
  public String toString() {
    return value == null ? materialize() : value.toString();
  }

  private String materialize() {
    String current = materializedValue;
    if (current != null) {
      return current;
    }
    StringBuilder result = new StringBuilder();
    if (includeRandomValue) {
      appendManagedField(
          result, RANDOM_VALUE_KEY, randomValue, randomValueStart, randomValueEnd, false);
    }
    if (includeThreshold) {
      appendManagedField(result, THRESHOLD_KEY, threshold, thresholdStart, thresholdEnd, true);
    }
    appendUnknownFields(result);
    current = result.toString();
    materializedValue = current;
    return current;
  }

  private void appendManagedField(
      StringBuilder result,
      String key,
      long numericValue,
      int sourceStart,
      int sourceEnd,
      boolean trimTrailingZeros) {
    appendSeparator(result);
    result.append(key);
    if (fields != null && sourceStart >= 0) {
      result.append(fields, sourceStart, sourceEnd);
    } else {
      appendHex(result, numericValue, trimTrailingZeros);
    }
  }

  private void appendUnknownFields(StringBuilder result) {
    if (fields == null) {
      return;
    }
    int start = 0;
    while (start < fields.length()) {
      int end = indexOf(fields, ';', start);
      if (end < 0) {
        end = fields.length();
      }
      if (!startsWith(fields, start, end, RANDOM_VALUE_KEY)
          && !startsWith(fields, start, end, THRESHOLD_KEY)) {
        appendSeparator(result);
        result.append(fields, start, end);
      }
      start = end + 1;
    }
  }

  private boolean hasUnknownFields() {
    if (fields == null) {
      return false;
    }
    int start = 0;
    while (start < fields.length()) {
      int end = indexOf(fields, ';', start);
      if (end < 0) {
        end = fields.length();
      }
      if (!startsWith(fields, start, end, RANDOM_VALUE_KEY)
          && !startsWith(fields, start, end, THRESHOLD_KEY)) {
        return true;
      }
      start = end + 1;
    }
    return false;
  }

  private static CharSequence normalize(
      CharSequence raw,
      int randomValueStart,
      int randomValueEnd,
      int thresholdStart,
      int thresholdEnd) {
    StringBuilder result = new StringBuilder(raw.length());
    if (randomValueStart >= 0) {
      appendRange(result, RANDOM_VALUE_KEY, raw, randomValueStart, randomValueEnd);
    }
    if (thresholdStart >= 0) {
      appendRange(result, THRESHOLD_KEY, raw, thresholdStart, thresholdEnd);
    }
    int start = 0;
    while (start < raw.length()) {
      int end = indexOf(raw, ';', start);
      if (end < 0) {
        end = raw.length();
      }
      if (!startsWith(raw, start, end, RANDOM_VALUE_KEY)
          && !startsWith(raw, start, end, THRESHOLD_KEY)
          && isUnknownField(raw, start, end)) {
        appendSeparator(result);
        result.append(raw, start, end);
      }
      start = end + 1;
    }
    return result.toString();
  }

  private static void appendRange(
      StringBuilder result, String key, CharSequence source, int start, int end) {
    appendSeparator(result);
    result.append(key).append(source, start, end);
  }

  private static void appendSeparator(StringBuilder result) {
    if (result.length() > 0) {
      result.append(';');
    }
  }

  private static void appendHex(StringBuilder result, long value, boolean trimTrailingZeros) {
    int digits = 14;
    if (trimTrailingZeros) {
      long remaining = value;
      while (digits > 1 && (remaining & 0xf) == 0) {
        remaining >>>= 4;
        digits--;
      }
    }
    for (int i = 13; i >= 14 - digits; i--) {
      int digit = (int) ((value >>> (i * 4)) & 0xf);
      result.append((char) (digit < 10 ? '0' + digit : 'a' + digit - 10));
    }
  }

  private static int indexOf(CharSequence value, char target, int start) {
    for (int i = start; i < value.length(); i++) {
      if (value.charAt(i) == target) {
        return i;
      }
    }
    return -1;
  }

  private static boolean startsWith(CharSequence value, int start, int end, CharSequence prefix) {
    if (end - start < prefix.length()) {
      return false;
    }
    for (int i = 0; i < prefix.length(); i++) {
      if (value.charAt(start + i) != prefix.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  private static boolean isUnknownField(CharSequence value, int start, int end) {
    int separator = indexOf(value, ':', start);
    return separator > start && separator < end - 1;
  }

  private static boolean isLowerHex(
      CharSequence value, int start, int end, int minimumLength, int maximumLength) {
    int length = end - start;
    if (length < minimumLength || length > maximumLength) {
      return false;
    }
    for (int i = start; i < end; i++) {
      if (!PTagsCodec.isHexDigit(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static long parseHex(CharSequence value, int start, int end) {
    if (start < 0) {
      return -1;
    }
    long parsed = 0;
    for (int i = start; i < end; i++) {
      char c = value.charAt(i);
      parsed = (parsed << 4) | (c <= '9' ? c - '0' : c - 'a' + 10);
    }
    return parsed;
  }

  private static long parseThreshold(CharSequence value, int start, int end) {
    if (start < 0) {
      return -1;
    }
    return parseHex(value, start, end) << (4 * (14 - (end - start)));
  }
}
