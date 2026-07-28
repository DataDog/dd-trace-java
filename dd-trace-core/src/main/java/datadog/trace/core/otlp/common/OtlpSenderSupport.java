package datadog.trace.core.otlp.common;

import static datadog.communication.http.OkHttpUtils.sendWithRetries;
import static datadog.trace.common.writer.RemoteApi.Response.failed;
import static datadog.trace.common.writer.RemoteApi.Response.success;

import datadog.communication.http.HttpRetryPolicy;
import datadog.logging.RatelimitedLogger;
import datadog.trace.common.writer.RemoteApi;
import java.io.IOException;
import okhttp3.OkHttpClient;

/** Shared request execution and response handling for {@link OtlpSender} implementations. */
final class OtlpSenderSupport {
  private OtlpSenderSupport() {}

  /** Executes the given request with retries, logging failures via the rate-limited logger. */
  static RemoteApi.Response send(
      OkHttpClient client,
      HttpRetryPolicy.Factory retryPolicy,
      okhttp3.Request request,
      RatelimitedLogger ratelimitedLogger) {
    try (okhttp3.Response response = sendWithRetries(client, retryPolicy, request)) {
      if (response.isSuccessful()) {
        return success(response.code());
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
}
