package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpClient;

/**
 * Factory for creating HttpClient instances. This factory selects the appropriate
 * implementation based on configuration and Java version.
 *
 * <p>For now, this uses OkHttp implementation. In Phase 4, this will be enhanced
 * to support JDK HttpClient based on Java version detection and configuration.
 */
final class HttpClientFactory {

  private HttpClientFactory() {
    // Utility class
  }

  /**
   * Creates a new builder for constructing HTTP clients.
   *
   * @return a new Builder
   */
  static HttpClient.Builder newBuilder() {
    return new OkHttpClient.OkHttpClientBuilder();
  }
}
