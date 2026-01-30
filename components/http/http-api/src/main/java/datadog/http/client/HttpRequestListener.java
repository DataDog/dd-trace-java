package datadog.http.client;

import java.io.IOException;
import javax.annotation.Nullable;

/**
 * Listener for HTTP request lifecycle events. Implementations can track request timing,
 * log requests, or handle errors.
 *
 * <p>This replaces the OkHttp-specific CustomListener/EventListener pattern with a
 * clean abstraction that works across implementations.
 */
public interface HttpRequestListener {
  /**
   * Called when a request is about to be sent.
   *
   * @param request the request being sent
   */
  void onRequestStart(HttpRequest request);

  /**
   * Called when a response is received successfully.
   *
   * @param request the request that was sent
   * @param response the response received, or null if response body hasn't been read yet
   */
  void onRequestEnd(HttpRequest request, @Nullable HttpResponse response);

  /**
   * Called when a request fails with an exception.
   *
   * @param request the request that failed
   * @param exception the exception that occurred
   */
  void onRequestFailure(HttpRequest request, IOException exception);
}
