package datadog.trace.bootstrap.instrumentation.rum;

public interface RumControllableResponse {
  /** Drain the held buffer. */
  void commit();

  /** Stops filtering the response. */
  void stopFiltering();
}
