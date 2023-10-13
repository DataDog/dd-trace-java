package datadog.appsec.api.blocking;

public class BlockingException extends RuntimeException {
  public BlockingException() {}

  public BlockingException(String message) {
    super(message);
  }

  public BlockingException(String message, Throwable cause) {
    super(message, cause);
  }

  public BlockingException(Throwable cause) {
    super(cause);
  }
}
