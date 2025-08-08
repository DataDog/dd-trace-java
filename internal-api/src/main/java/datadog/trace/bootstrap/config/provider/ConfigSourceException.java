package datadog.trace.bootstrap.config.provider;

/**
 * Exception thrown when a ConfigProvider.Source encounters an error (e.g., parsing, IO, or format
 * error) while retrieving a configuration value.
 */
public class ConfigSourceException extends Exception {
  private final Object rawValue;

  public ConfigSourceException(String message, Object rawValue, Throwable cause) {
    super(message, cause);
    this.rawValue = rawValue;
  }

  public ConfigSourceException(Object rawValue) {
    this.rawValue = rawValue;
  }

  /** Returns the raw value that caused the exception, if available. */
  public Object getRawValue() {
    return rawValue;
  }
}
