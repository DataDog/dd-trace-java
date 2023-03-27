package datadog.appsec.api.blocking;

public enum BlockingContentType {
  /**
   * Automatically choose between HTML and JSON, depending on the value of the <code>Accept</code>
   * header. If the preference value is the same, {@link #JSON} will be preferred.
   */
  AUTO,
  /** An HTTP response. */
  HTML,
  /** A JSON response. */
  JSON,
  /** No body in the response */
  NONE,
}
