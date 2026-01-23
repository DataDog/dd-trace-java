package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpRequest;
import java.lang.reflect.Constructor;

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
  static HttpRequest.Builder newBuilder() {
    if (HttpClientFactory.isUsingJdkImplementation()) {
      try {
        Class<?> builderClass = Class.forName("datadog.communication.http.jdk.JdkHttpRequest$JdkHttpRequestBuilder");
        Constructor<?> constructor = builderClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (HttpRequest.Builder) constructor.newInstance();
      } catch (Exception e) {
        throw new RuntimeException("Failed to create JDK request builder", e);
      }
    } else {
      return new OkHttpRequest.OkHttpRequestBuilder();
    }
  }
}
