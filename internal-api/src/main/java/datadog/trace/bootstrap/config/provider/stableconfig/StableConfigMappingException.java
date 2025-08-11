package datadog.trace.bootstrap.config.provider.stableconfig;

public class StableConfigMappingException extends RuntimeException {
  private static final int MAX_LEN = 100;

  public StableConfigMappingException(String message) {
    super(message);
  }

  static String safeToString(Object value) {
    if (value == null) return "null";
    String str = value.toString();
    if (str.length() > MAX_LEN) {
      return str.substring(0, MAX_LEN) + "...(truncated)";
    }
    return str;
  }

  public static void throwStableConfigMappingException(String message, Object value) {
    throw new StableConfigMappingException(message + " " + safeToString(value));
  }
}
