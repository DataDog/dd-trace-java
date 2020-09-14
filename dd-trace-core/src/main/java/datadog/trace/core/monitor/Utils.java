package datadog.trace.core.monitor;

import java.util.Arrays;

public class Utils {
  static String[] mergeTags(String[] left, String[] right) {
    if (null == right) {
      return left;
    }
    String[] merged = Arrays.copyOf(left, left.length + right.length);
    System.arraycopy(right, 0, merged, left.length, right.length);
    return merged;
  }
}
