package datadog.trace.bootstrap.config.provider.stableconfig;

public class StableConfigMappingException extends RuntimeException {
  public StableConfigMappingException(String message) {
    super(message);
  }

  public StableConfigMappingException(String message, Throwable cause) {
    super(message, cause);
  }
}
