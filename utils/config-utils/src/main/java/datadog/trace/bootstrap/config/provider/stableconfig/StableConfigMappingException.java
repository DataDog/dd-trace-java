package datadog.trace.bootstrap.config.provider.stableconfig;

public class StableConfigMappingException extends RuntimeException {
  private static final int MAX_LEN = 100;

  public StableConfigMappingException(String message) {
    super(message);
  }

  /**
   * Safely converts an object to a string for error reporting, truncating the result if it exceeds
   * a maximum length.
   *
   * @param value the object to convert to a string
   * @return a string representation of the object, truncated if necessary
   */
  static String safeToString(Object value) {
    if (value == null) return "null";
    String str = value.toString();
    if (str.length() > MAX_LEN) {
      int partLen = MAX_LEN / 2;
      return str.substring(0, partLen)
          + "...(truncated)..."
          + str.substring(str.length() - partLen);
    }
    return str;
  }

  /**
   * Throws a StableConfigMappingException with a message that includes a safe string representation
   * of the provided value.
   *
   * @param message the error message to include
   * @param value the value to include in the error message, safely stringified
   * @throws StableConfigMappingException always thrown by this method
   */
  public static void throwStableConfigMappingException(String message, Object value) {
    throw new StableConfigMappingException(message + " " + safeToString(value));
  }
}
