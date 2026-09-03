package datadog.trace.core.propagation.ptags;

import java.util.ArrayList;
import java.util.List;

final class OtelTraceState {
  static final int MAX_VALUE_LENGTH = 256;

  private static final int HEX_DIGITS = 14;
  private static final long KNUTH_FACTOR = 1111111111111111111L;
  private static final long MAX_THRESHOLD = (1L << 56) - 1;
  private static final double THRESHOLD_RANGE = 1L << 56;

  private final String value;
  private final String randomValue;
  private final String threshold;
  private final String[] unknownFields;
  private final int randomValueCount;
  private final int inheritedPosition;
  private final boolean locallyGeneratedRandomValue;

  private OtelTraceState(
      String value,
      String randomValue,
      String threshold,
      String[] unknownFields,
      int randomValueCount,
      int inheritedPosition,
      boolean locallyGeneratedRandomValue) {
    this.value = value;
    this.randomValue = randomValue;
    this.threshold = threshold;
    this.unknownFields = unknownFields;
    this.randomValueCount = randomValueCount;
    this.inheritedPosition = inheritedPosition;
    this.locallyGeneratedRandomValue = locallyGeneratedRandomValue;
  }

  static OtelTraceState parse(String raw, int inheritedPosition) {
    if (raw == null || raw.isEmpty()) {
      return null;
    }

    List<String> fields = new ArrayList<>();
    List<String> unknownFields = new ArrayList<>();
    String randomValue = null;
    String threshold = null;
    int randomValueCount = 0;
    boolean changed = false;
    int start = 0;
    while (start < raw.length()) {
      int end = raw.indexOf(';', start);
      if (end < 0) {
        end = raw.length();
      }
      String field = raw.substring(start, end);
      int separator = field.indexOf(':');
      String key = separator > 0 ? field.substring(0, separator) : field;
      String fieldValue = separator > 0 ? field.substring(separator + 1) : "";
      if ("rv".equals(key)) {
        if (fieldValue.length() == HEX_DIGITS && isLowercaseHex(fieldValue)) {
          fields.add(field);
          if (randomValue == null) {
            randomValue = fieldValue;
          }
          randomValueCount++;
        } else {
          changed = true;
        }
      } else if ("th".equals(key)) {
        if (!fieldValue.isEmpty()
            && fieldValue.length() <= HEX_DIGITS
            && isLowercaseHex(fieldValue)) {
          fields.add(field);
          if (threshold == null) {
            threshold = fieldValue;
          }
        } else {
          changed = true;
        }
      } else if (!field.isEmpty()) {
        fields.add(field);
        unknownFields.add(field);
      } else {
        changed = true;
      }
      start = end + 1;
    }

    String value = join(fields);
    if (value == null) {
      return null;
    }
    if (!value.equals(raw)) {
      changed = true;
    }
    return new OtelTraceState(
        value,
        randomValue,
        threshold,
        unknownFields.toArray(new String[0]),
        randomValueCount,
        changed ? 0 : inheritedPosition,
        false);
  }

  static OtelTraceState updateProbability(
      OtelTraceState current,
      long traceIdLowOrderBits,
      double sampleRate,
      boolean sampled,
      int samplingPriority) {
    // `sampled` is the raw probability result; `samplingPriority` may be changed by rate limiting.
    if (sampleRate <= 0) {
      return current == null ? null : current.removeLocalProbability();
    }

    if (sampled && samplingPriority <= 0) {
      return current == null ? null : current.removeForNonProbabilityDecision();
    }

    long threshold = computeThreshold(sampleRate);
    long randomValue = computeRandomValue(traceIdLowOrderBits);
    if (sampled && randomValue < threshold) {
      randomValue = threshold;
    } else if (!sampled && randomValue >= threshold) {
      randomValue = threshold == 0 ? 0 : threshold - 1;
    }

    String[] unknownFields = current == null ? new String[0] : current.unknownFields;
    return create(
        formatRandomValue(randomValue), formatThreshold(threshold), unknownFields, 0, true);
  }

  OtelTraceState removeForNonProbabilityDecision() {
    if (!locallyGeneratedRandomValue && threshold == null && randomValueCount <= 1) {
      return this;
    }
    String retainedRandomValue = locallyGeneratedRandomValue ? null : randomValue;
    return create(retainedRandomValue, null, unknownFields, 0, false);
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

  private OtelTraceState removeLocalProbability() {
    return locallyGeneratedRandomValue ? create(null, null, unknownFields, 0, false) : this;
  }

  private static OtelTraceState create(
      String randomValue,
      String threshold,
      String[] unknownFields,
      int inheritedPosition,
      boolean locallyGeneratedRandomValue) {
    StringBuilder value = new StringBuilder();
    append(value, randomValue == null ? null : "rv:" + randomValue);
    append(value, threshold == null ? null : "th:" + threshold);
    for (String field : unknownFields) {
      append(value, field);
    }
    if (value.length() == 0) {
      return null;
    }
    return new OtelTraceState(
        value.toString(),
        randomValue,
        threshold,
        unknownFields,
        randomValue == null ? 0 : 1,
        inheritedPosition,
        locallyGeneratedRandomValue);
  }

  private static void append(StringBuilder value, String field) {
    if (field == null || field.isEmpty()) {
      return;
    }
    int separatorSize = value.length() == 0 ? 0 : 1;
    if (value.length() + separatorSize + field.length() > MAX_VALUE_LENGTH) {
      return;
    }
    if (separatorSize != 0) {
      value.append(';');
    }
    value.append(field);
  }

  private static String join(List<String> fields) {
    if (fields.isEmpty()) {
      return null;
    }
    StringBuilder value = new StringBuilder();
    for (String field : fields) {
      if (value.length() != 0) {
        value.append(';');
      }
      value.append(field);
    }
    return value.toString();
  }

  private static boolean isLowercaseHex(String value) {
    for (int i = 0; i < value.length(); i++) {
      char character = value.charAt(i);
      if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) {
        return false;
      }
    }
    return true;
  }

  private static long computeRandomValue(long traceIdLowOrderBits) {
    return (~(traceIdLowOrderBits * KNUTH_FACTOR)) >>> 8;
  }

  private static long computeThreshold(double sampleRate) {
    long threshold = Math.round((1 - sampleRate) * THRESHOLD_RANGE);
    return Math.max(0, Math.min(threshold, MAX_THRESHOLD));
  }

  private static String formatRandomValue(long randomValue) {
    String hex = Long.toHexString(randomValue);
    if (hex.length() == HEX_DIGITS) {
      return hex;
    }
    StringBuilder padded = new StringBuilder(HEX_DIGITS);
    for (int i = hex.length(); i < HEX_DIGITS; i++) {
      padded.append('0');
    }
    return padded.append(hex).toString();
  }

  private static String formatThreshold(long threshold) {
    String hex = formatRandomValue(threshold);
    int end = hex.length();
    while (end > 1 && hex.charAt(end - 1) == '0') {
      end--;
    }
    return hex.substring(0, end);
  }
}
