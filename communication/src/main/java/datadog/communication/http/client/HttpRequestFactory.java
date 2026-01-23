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
    if (HttpClientFactory.isUsingJdkImplementation()) {
      if (!JdkHttpClientSupport.isAvailable()) {
        throw new RuntimeException("JDK HttpRequest builder not available");
      }
      try {
        // Use cached reflection to create JdkHttpRequest.JdkHttpRequestBuilder
        return (HttpRequest.Builder) JdkHttpClientSupport.JDK_REQUEST_BUILDER_CONSTRUCTOR.newInstance();
      } catch (Exception e) {
        throw new RuntimeException("Failed to create JDK request builder", e);
      }
    } else {
      return new OkHttpRequest.OkHttpRequestBuilder();
    }
  }
}
