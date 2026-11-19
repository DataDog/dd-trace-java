package datadog.trace.core.otlp.sdk;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;

/**
 * Maps export failures to bounded {@code error.type} values. A non-success status code is reported
 * as {@code Integer.toString(statusCode)}.
 */
final class ErrorTypes {
  static final String TIMEOUT = "timeout";

  /** Defensive fallback: current senders always report a status code or an exception. */
  static final String OTHER = "_OTHER";

  private ErrorTypes() {}

  /**
   * {@code timeout} for I/O timeouts: a {@link SocketTimeoutException}, or OkHttp's call timeout
   * (an {@link InterruptedIOException} with message {@code "timeout"}). Otherwise the exception's
   * class name, so a send interrupted at shutdown is not counted as a timeout. gRPC status failures
   * are classified by the caller, by status name.
   */
  static String fromException(Throwable error) {
    return isTimeout(error) ? TIMEOUT : error.getClass().getName();
  }

  private static boolean isTimeout(Throwable error) {
    return error instanceof SocketTimeoutException
        || (error instanceof InterruptedIOException && "timeout".equals(error.getMessage()));
  }
}
