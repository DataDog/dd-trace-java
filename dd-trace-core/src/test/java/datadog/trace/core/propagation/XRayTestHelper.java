package datadog.trace.core.propagation;

public class XRayTestHelper {
  static String zeroPadId(String s) {
    if (s.length() >= 16) {
      return s;
    }
    StringBuilder sb = new StringBuilder(16);
    for (int i = 0; i < 16 - s.length(); i++) {
      sb.append('0');
    }
    sb.append(s);
    return sb.toString();
  }
}
