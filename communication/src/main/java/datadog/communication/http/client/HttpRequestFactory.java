package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpRequest;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.lang.reflect.Constructor;

/**
 * Factory for creating HttpRequest instances. This factory selects the appropriate
 * implementation based on configuration and Java version.
 */
final class HttpRequestFactory {

  // Cached JDK HttpRequest classes (loaded via reflection on Java 11+)
  private static final Class<?> JDK_REQUEST_BUILDER_CLASS;
  private static final Constructor<?> JDK_REQUEST_BUILDER_CONSTRUCTOR;

  static {
    Class<?> builderClass = null;
    Constructor<?> builderConstructor = null;
    try {
      builderClass = Class.forName("datadog.communication.http.jdk.JdkHttpRequest$JdkHttpRequestBuilder");
      builderConstructor = builderClass.getDeclaredConstructor();
      builderConstructor.setAccessible(true);
    } catch (ClassNotFoundException | NoSuchMethodException e) {
      // JDK HttpRequest not available
    }
    JDK_REQUEST_BUILDER_CLASS = builderClass;
    JDK_REQUEST_BUILDER_CONSTRUCTOR = builderConstructor;
  }

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
      if (JDK_REQUEST_BUILDER_CONSTRUCTOR == null) {
        throw new RuntimeException("JDK HttpRequest builder not available");
      }
      try {
        // Use cached reflection to create JdkHttpRequest.JdkHttpRequestBuilder
        return (HttpRequest.Builder) JDK_REQUEST_BUILDER_CONSTRUCTOR.newInstance();
      } catch (Exception e) {
        throw new RuntimeException("Failed to create JDK request builder", e);
      }
    } else {
      return new OkHttpRequest.OkHttpRequestBuilder();
    }
  }
}
