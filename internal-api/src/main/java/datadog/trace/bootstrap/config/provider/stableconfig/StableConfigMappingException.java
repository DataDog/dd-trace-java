package datadog.trace.bootstrap.config.provider.stableconfig;

public class StableConfigMappingException extends RuntimeException {
  public StableConfigMappingException(String message) {
    super(message);
  }

  public StableConfigMappingException(String message, Throwable cause) {
    super(message, cause);
  }

  public static String safeToString(Object value) {
    if (value == null) return "null";
    String str = value.toString();
    if (str.length() > 100) {
      return str.substring(0, 100) + "...(truncated)";
    }
    return str;
  }
}
