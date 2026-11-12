package datadog.trace.core.otlp.common;

import static datadog.communication.http.OkHttpUtils.sendWithRetries;
import static datadog.trace.common.writer.RemoteApi.Response.failed;
import static datadog.trace.common.writer.RemoteApi.Response.success;

import datadog.communication.http.HttpRetryPolicy;
import datadog.logging.RatelimitedLogger;
import datadog.trace.common.writer.RemoteApi;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import javax.annotation.Nullable;
import okhttp3.OkHttpClient;

/** Shared request execution and response handling for {@link OtlpSender} implementations. */
final class OtlpSenderSupport {
  private static final int GRPC_OK = 0;
  private static final int GRPC_UNKNOWN = 2;
  private static final String GRPC_CONTENT_TYPE = "application/grpc";

  private OtlpSenderSupport() {}

  /** Executes the given HTTP request with retries, logging failures via the rate-limited logger. */
  static RemoteApi.Response send(
      OkHttpClient client,
      HttpRetryPolicy.Factory retryPolicy,
      okhttp3.Request request,
      RatelimitedLogger ratelimitedLogger) {
    return send(client, retryPolicy, request, ratelimitedLogger, false);
  }

  /**
   * Executes the given request with retries, logging failures via the rate-limited logger.
   *
   * @param grpc whether the request is a gRPC call, whose outcome is its gRPC status
   */
  static RemoteApi.Response send(
      OkHttpClient client,
      HttpRetryPolicy.Factory retryPolicy,
      okhttp3.Request request,
      RatelimitedLogger ratelimitedLogger,
      boolean grpc) {
    try (okhttp3.Response response = sendWithRetries(client, retryPolicy, request)) {
      if (response.isSuccessful()) {
        IOException grpcError = grpc ? grpcError(response) : null;
        if (grpcError == null) {
          return success(response.code());
        }
        ratelimitedLogger.warn(
            "OTLP export to {} failed with {}", request.url(), grpcError.getMessage());
        return failed(grpcError);
      }
      ratelimitedLogger.warn(
          "OTLP export to {} failed with status {}: {}",
          request.url(),
          response.code(),
          response.message());
      return failed(response.code());
    } catch (IOException e) {
      ratelimitedLogger.warn(
          "OTLP export to {} failed with exception: {}", request.url(), e.toString());
      return failed(e);
    }
  }

  /**
   * Returns the error of a 2xx gRPC response, e.g. {@code gRPC status 14: unavailable}, or null if
   * the call succeeded.
   *
   * <p>Only header statuses ("Trailers-Only" responses) are read: OkHttp 3.12 can't read trailers.
   * Without a status header, a gRPC content type is assumed OK; other content types fail as {@code
   * UNKNOWN}.
   */
  @Nullable
  private static IOException grpcError(okhttp3.Response response) {
    String status = response.header("grpc-status");
    if (status == null) {
      String contentType = response.header("Content-Type");
      if (isGrpcContentType(contentType)) {
        return null; // status is in the trailers, which we can't read
      }
      return new IOException(
          "gRPC status " + GRPC_UNKNOWN + " (no grpc-status, content-type: " + contentType + ")");
    }
    int code;
    try {
      code = Integer.parseInt(status.trim());
    } catch (NumberFormatException e) {
      code = GRPC_UNKNOWN;
    }
    if (code == GRPC_OK) {
      return null;
    }
    String grpcMessage = response.header("grpc-message");
    return new IOException(
        "gRPC status " + code + (grpcMessage == null ? "" : ": " + decodeGrpcMessage(grpcMessage)));
  }

  /**
   * Whether the content type is {@code application/grpc}, optionally followed by a {@code +format}
   * suffix or parameters. Media types are case-insensitive; {@code application/grpc-web} is a
   * different protocol and doesn't match.
   */
  private static boolean isGrpcContentType(@Nullable String contentType) {
    int length = GRPC_CONTENT_TYPE.length();
    if (contentType == null || !contentType.regionMatches(true, 0, GRPC_CONTENT_TYPE, 0, length)) {
      return false;
    }
    if (contentType.length() == length) {
      return true;
    }
    char next = contentType.charAt(length);
    return next == '+' || next == ';';
  }

  /**
   * Decodes a {@code grpc-message}, which is percent-encoded UTF-8. A literal {@code +} stays as
   * is, and a message with a malformed escape is kept undecoded.
   */
  private static String decodeGrpcMessage(String message) {
    if (message.indexOf('%') == -1) {
      return message;
    }
    try {
      // percent encoding, not form encoding: '+' isn't a space
      return URLDecoder.decode(message.replace("+", "%2B"), "UTF-8");
    } catch (UnsupportedEncodingException | IllegalArgumentException e) {
      return message;
    }
  }
}
