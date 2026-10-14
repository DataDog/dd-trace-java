package datadog.communication.otlp;

import static datadog.communication.http.OkHttpUtils.sendWithRetries;
import static datadog.communication.otlp.OtlpResponse.failed;
import static datadog.communication.otlp.OtlpResponse.success;

import datadog.communication.http.HttpRetryPolicy;
import datadog.logging.RatelimitedLogger;
import java.io.IOException;
import okhttp3.OkHttpClient;

/** Shared request execution and response handling for {@link OtlpSender} implementations. */
final class OtlpSenderSupport {
  private OtlpSenderSupport() {}

  /**
   * Sends the request and maps every outcome to an {@link OtlpResponse}. Transport failures are
   * returned instead of thrown, so a broken endpoint degrades to logged warnings and cannot break
   * the caller's export loop.
   */
  static OtlpResponse send(
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
