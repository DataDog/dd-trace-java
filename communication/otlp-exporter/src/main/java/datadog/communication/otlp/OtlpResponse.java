package datadog.communication.otlp;

import java.util.Optional;
import java.util.OptionalInt;

/**
 * The outcome of a single OTLP export attempt.
 *
 * <p>A transport failure or timeout carries only the exception: the payload was not delivered, so
 * no status code exists. A delivered payload carries the HTTP status code and is successful or not
 * according to that code. A successful response can still carry a parse exception when its body
 * could not be read; the payload itself was accepted.
 */
public final class OtlpResponse {
  /** The endpoint accepted the payload and returned no body. */
  public static OtlpResponse success(final int status) {
    return new OtlpResponse(true, status, null, null);
  }

  /** The endpoint accepted the payload and returned a body. */
  public static OtlpResponse success(final int status, String response) {
    return new OtlpResponse(true, status, null, response);
  }

  /** The endpoint accepted the payload, but the response body could not be parsed. */
  public static OtlpResponse successWithParseError(final int status, final Throwable exception) {
    return new OtlpResponse(true, status, exception, null);
  }

  /** The endpoint received the payload but rejected it. */
  public static OtlpResponse failed(final int status) {
    return new OtlpResponse(false, status, null, null);
  }

  /** The endpoint rejected the payload; the body may carry diagnostics. */
  public static OtlpResponse failed(final int status, String response) {
    return new OtlpResponse(false, status, null, response);
  }

  /** The payload never reached the endpoint. */
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

  /** The raw response body, or empty for failed communication attempts (no body received) */
  public Optional<String> response() {
    return Optional.ofNullable(response);
  }
}
