package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpRequest;
import de.thetaphi.forbiddenapis.SuppressForbidden;

/**
 * Factory for creating HttpRequest instances. This factory selects the appropriate
 * implementation based on configuration and Java version.
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
  @SuppressForbidden // Dynamically load JDK11+ version
  static HttpRequest.Builder newBuilder() {
    // Try JDK HttpClient implementation if available
    if (JdkHttpClientSupport.isAvailable()) {
      try {
        // Use cached reflection to create JdkHttpRequest.JdkHttpRequestBuilder
        return (HttpRequest.Builder) JdkHttpClientSupport.JDK_REQUEST_BUILDER_CONSTRUCTOR.newInstance();
      } catch (Exception e) {
        // Fall through to OkHttp implementation
      }
    }

    // Use OkHttp implementation (fallback or default)
    return new OkHttpRequest.OkHttpRequestBuilder();
  }
}
