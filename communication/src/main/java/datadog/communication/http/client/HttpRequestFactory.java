package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpRequest;

/**
 * Factory for creating HttpRequest instances. This factory selects the appropriate
 * implementation based on configuration and Java version.
 *
 * <p>For now, this uses OkHttp implementation. In Phase 2, Task 2.3, this will be enhanced
 * to support JDK HttpClient based on Java version detection and configuration.
 */
final class HttpRequestFactory {

  private HttpRequestFactory() {
    // Utility class
  }

  /**
   * Creates a new builder for constructing HTTP requests.
   *
   * @return a new Builder
   */
  static HttpRequest.Builder newBuilder() {
    return new OkHttpRequest.OkHttpRequestBuilder();
  }
}
