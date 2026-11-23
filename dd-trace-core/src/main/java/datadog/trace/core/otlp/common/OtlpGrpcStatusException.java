package datadog.trace.core.otlp.common;

import java.io.IOException;

/** A gRPC call that completed with a non-OK {@code grpc-status}. */
public final class OtlpGrpcStatusException extends IOException {
  private static final String[] STATUS_NAMES = {
    "OK",
    "CANCELLED",
    "UNKNOWN",
    "INVALID_ARGUMENT",
    "DEADLINE_EXCEEDED",
    "NOT_FOUND",
    "ALREADY_EXISTS",
    "PERMISSION_DENIED",
    "RESOURCE_EXHAUSTED",
    "FAILED_PRECONDITION",
    "ABORTED",
    "OUT_OF_RANGE",
    "UNIMPLEMENTED",
    "INTERNAL",
    "UNAVAILABLE",
    "DATA_LOSS",
    "UNAUTHENTICATED"
  };

  private final int statusCode;

  public OtlpGrpcStatusException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }

  /** The canonical name of the status code, e.g. {@code UNAVAILABLE} for 14. */
  public String statusName() {
    return statusName(statusCode);
  }

  /** The canonical name of a gRPC status code; codes outside 0-16 keep their number. */
  public static String statusName(int statusCode) {
    return statusCode >= 0 && statusCode < STATUS_NAMES.length
        ? STATUS_NAMES[statusCode]
        : Integer.toString(statusCode);
  }
}
