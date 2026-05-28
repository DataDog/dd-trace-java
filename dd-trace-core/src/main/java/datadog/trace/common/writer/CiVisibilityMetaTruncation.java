package datadog.trace.common.writer;

public final class CiVisibilityMetaTruncation {
  public static final int MAX_META_STRING_VALUE_LENGTH = 5000;

  private CiVisibilityMetaTruncation() {}

  public static String truncate(String value) {
    if (value == null || value.length() <= MAX_META_STRING_VALUE_LENGTH) {
      return value;
    }
    return value.substring(0, MAX_META_STRING_VALUE_LENGTH);
  }
}
