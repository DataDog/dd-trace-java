package datadog.communication.otlp;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * Encapsulates the result of an OTLP export attempt.
 *
 * <p>If communication fails or times out, the response will NOT be successful and will lack a
 * status code, but will have an exception.
 *
 * <p>If communication succeeds, the response will have a status code and will be marked as success
 * or failure in accordance with the code.
 *
 * <p>NOTE: A successful communication may still contain an exception if there was a problem parsing
 * the response.
 */
public final class OtlpResponse {
  /** Factory method for a successful request with a trivial response body */
  public static OtlpResponse success(final int status) {
    return new OtlpResponse(true, status, null, null);
  }

  /** Factory method for a successful request with a response body */
  public static OtlpResponse success(final int status, String response) {
    return new OtlpResponse(true, status, null, response);
  }

  /** Factory method for a successful request with a malformed response body */
  public static OtlpResponse success(final int status, final Throwable exception) {
    return new OtlpResponse(true, status, exception, null);
  }

  /** Factory method for a request that received an error status in response */
  public static OtlpResponse failed(final int status) {
    return new OtlpResponse(false, status, null, null);
  }

  /** Factory method for a request that received an error status and a response body */
  public static OtlpResponse failed(final int status, String response) {
    return new OtlpResponse(false, status, null, response);
  }

  /** Factory method for a failed communication attempt */
  public static OtlpResponse failed(final Throwable exception) {
    return new OtlpResponse(false, null, exception, null);
  }

  private final boolean success;
  private final Integer status;
  private final Throwable exception;
  private final String response;

  private OtlpResponse(
      final boolean success, final Integer status, final Throwable exception, String response) {
    this.success = success;
    this.status = status;
    this.exception = exception;
    this.response = response;
  }

  public boolean success() {
    return success;
  }

  public OptionalInt status() {
    return status == null ? OptionalInt.empty() : OptionalInt.of(status);
  }

  public Optional<Throwable> exception() {
    return Optional.ofNullable(exception);
  }

  public String response() {
    return response;
  }
}
