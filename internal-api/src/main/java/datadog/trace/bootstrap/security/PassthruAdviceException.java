package datadog.trace.bootstrap.security;

public class PassthruAdviceException extends RuntimeException {
  public PassthruAdviceException(Throwable cause) {
    super(cause);
  }
}
