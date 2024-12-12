package datadog.remoteconfig.tuf;

public class MissingContentException extends RuntimeException {
  public MissingContentException(String message) {
    super(message);
  }
}
