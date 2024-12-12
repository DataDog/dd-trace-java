package datadog.remoteconfig;

/* An exception that should be reported in client.state.error */
public class ReportableException extends RuntimeException {
  public ReportableException(String message) {
    super(message);
  }

  public ReportableException(String message, Throwable t) {
    super(message, t);
  }
}
