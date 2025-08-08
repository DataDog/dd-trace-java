package datadog.trace.bootstrap.config.provider.stableconfig;

public class StableConfigMappingException extends RuntimeException {
  private static final int maxLen = 10;

  public StableConfigMappingException(String message) {
    super(message);
  }

  public static String safeToString(Object value) {
    if (value == null) return "null";
    String str = value.toString();
    if (str.length() > maxLen) {
      return str.substring(0, maxLen) + "...(truncated)";
    }
    return str;
  }
}
