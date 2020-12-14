package datadog.trace.common.writer.ddagent;

/**
 * Encapsulates an attempted response from the Datadog agent.
 *
 * <p>If communication fails or times out, the Response will NOT be successful and will lack status
 * code, but will have an exception.
 *
 * <p>If an communication occurs, the Response will have a status code and will be marked as success
 * or fail in accordance with the code.
 *
 * <p>NOTE: A successful communication may still contain an exception if there was a problem parsing
 * the response from the Datadog agent.
 */
public final class Response {
  /** Factory method for a successful request with a trivial response body */
  public static Response success(final int status) {
    return new Response(true, status, null, null);
  }

  /** Factory method for a successful request with a trivial response body */
  public static Response success(final int status, String response) {
    return new Response(true, status, null, response);
  }

  /** Factory method for a successful request will a malformed response body */
  public static Response success(final int status, final Throwable exception) {
    return new Response(true, status, exception, null);
  }

  /** Factory method for a request that receive an error status in response */
  public static Response failed(final int status) {
    return new Response(false, status, null, null);
  }

  /** Factory method for a failed communication attempt */
  public static Response failed(final Throwable exception) {
    return new Response(false, null, exception, null);
  }

  private final boolean success;
  private final Integer status;
  private final Throwable exception;
  private final String response;

  private Response(
      final boolean success, final Integer status, final Throwable exception, String response) {
    this.success = success;
    this.status = status;
    this.exception = exception;
    this.response = response;
  }

  public final boolean success() {
    return success;
  }

  // TODO: DQH - In Java 8, switch to OptionalInteger
  public final Integer status() {
    return status;
  }

  // TODO: DQH - In Java 8, switch to Optional<Throwable>?
  public final Throwable exception() {
    return exception;
  }

  public final String response() {
    return response;
  }
}
