package datadog.trace.core.propagation;

import datadog.trace.api.DDTraceId;

/**
 * This class contains helper methods for formatting trace and span identifiers for B3 propagation
 * tests.
 */
public final class B3TestHelper {

  private B3TestHelper() {}

  static String traceIdOrPadded(DDTraceId id, boolean padding) {
    if (id.toHighOrderLong() == 0) {
      return idOrPadded(Long.toHexString(id.toLong()), 32, padding);
    }
    return id.toHexString();
  }

  static String traceIdOrPadded(long id, boolean padding) {
    return idOrPadded(id, 32, padding);
  }

  static String traceIdOrPadded(String hexTraceId, boolean padding) {
    return idOrPadded(hexTraceId, 32, padding);
  }

  static String spanIdOrPadded(long id, boolean padding) {
    return idOrPadded(id, 16, padding);
  }

  static String spanIdOrPadded(String hexSpanId, boolean padding) {
    return idOrPadded(hexSpanId, 16, padding);
  }

  private static String idOrPadded(long id, int size, boolean padding) {
    return idOrPadded(Long.toHexString(id), size, padding);
  }

  private static String idOrPadded(String id, int size, boolean padding) {
    if (!padding) {
      return id.toLowerCase();
    }
    return padHexLower(id, size);
  }

  private static String padHexLower(String hex, int size) {
    String lower = hex.toLowerCase();
    int diff = size - lower.length();
    if (diff <= 0) {
      return lower;
    }
    StringBuilder sb = new StringBuilder(size);
    for (int i = 0; i < diff; i++) {
      sb.append('0');
    }
    sb.append(lower);
    return sb.toString();
  }

  static String trimHex(String hex) {
    int length = hex.length();
    int firstNonZero = 0;
    while (firstNonZero < length && hex.charAt(firstNonZero) == '0') {
      firstNonZero++;
    }
    if (firstNonZero == length) {
      return "0";
    }
    return hex.substring(firstNonZero, length);
  }
}
