package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpUrl;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;

/**
 * Factory for creating HttpUrl instances. This factory selects the appropriate implementation
 * based on configuration and Java version.
 */
final class HttpUrlFactory {

  private HttpUrlFactory() {
    // Utility class
  }

  /**
   * Parses a URL string into an HttpUrl.
   *
   * @param url the URL string to parse
   * @return the parsed HttpUrl
   * @throws IllegalArgumentException if the URL is malformed
   * @throws NullPointerException if url is null
   */
  static HttpUrl parse(String url) {
    Objects.requireNonNull(url, "url");

    // Determine which implementation to use based on HttpClientFactory
    if (HttpClientFactory.isUsingJdkImplementation()) {
      try {
        URI uri = new URI(url);
        // Use reflection to call JdkHttpUrl.wrap(uri)
        Class<?> jdkUrlClass = Class.forName("datadog.communication.http.jdk.JdkHttpUrl");
        Method wrapMethod = jdkUrlClass.getMethod("wrap", URI.class);
        return (HttpUrl) wrapMethod.invoke(null, uri);
      } catch (URISyntaxException e) {
        throw new IllegalArgumentException("Invalid URL: " + url, e);
      } catch (Exception e) {
        throw new RuntimeException("Failed to create JDK HttpUrl", e);
      }
    } else {
      okhttp3.HttpUrl okHttpUrl = okhttp3.HttpUrl.parse(url);
      if (okHttpUrl == null) {
        throw new IllegalArgumentException("Invalid URL: " + url);
      }
      return OkHttpUrl.wrap(okHttpUrl);
    }
  }

  /**
   * Creates a new builder for constructing URLs.
   *
   * @return a new Builder
   */
  static HttpUrl.Builder newBuilder() {
    if (HttpClientFactory.isUsingJdkImplementation()) {
      try {
        // Use reflection to create JdkHttpUrl.JdkHttpUrlBuilder
        Class<?> builderClass = Class.forName("datadog.communication.http.jdk.JdkHttpUrl$JdkHttpUrlBuilder");
        Constructor<?> constructor = builderClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        return (HttpUrl.Builder) constructor.newInstance();
      } catch (Exception e) {
        throw new RuntimeException("Failed to create JDK HttpUrl builder", e);
      }
    } else {
      return new OkHttpUrl.OkHttpUrlBuilder();
    }
  }
}
