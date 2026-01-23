package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpUrl;
import de.thetaphi.forbiddenapis.SuppressForbidden;
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

  // Cached JDK HttpUrl classes (loaded via reflection on Java 11+)
  private static final Class<?> JDK_URL_CLASS;
  private static final Method JDK_URL_WRAP_METHOD;
  private static final Class<?> JDK_URL_BUILDER_CLASS;
  private static final Constructor<?> JDK_URL_BUILDER_CONSTRUCTOR;

  static {
    Class<?> urlClass = null;
    Method wrapMethod = null;
    Class<?> builderClass = null;
    Constructor<?> builderConstructor = null;
    try {
      urlClass = Class.forName("datadog.communication.http.jdk.JdkHttpUrl");
      wrapMethod = urlClass.getMethod("wrap", URI.class);
      builderClass = Class.forName("datadog.communication.http.jdk.JdkHttpUrl$JdkHttpUrlBuilder");
      builderConstructor = builderClass.getDeclaredConstructor();
      builderConstructor.setAccessible(true);
    } catch (ClassNotFoundException | NoSuchMethodException e) {
      // JDK HttpUrl not available
    }
    JDK_URL_CLASS = urlClass;
    JDK_URL_WRAP_METHOD = wrapMethod;
    JDK_URL_BUILDER_CLASS = builderClass;
    JDK_URL_BUILDER_CONSTRUCTOR = builderConstructor;
  }

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
  @SuppressForbidden // Dynamically load JDK11+ version
  static HttpUrl parse(String url) {
    Objects.requireNonNull(url, "url");

    // Determine which implementation to use based on HttpClientFactory
    if (HttpClientFactory.isUsingJdkImplementation()) {
      if (JDK_URL_WRAP_METHOD == null) {
        throw new RuntimeException("JDK HttpUrl not available");
      }
      try {
        URI uri = new URI(url);
        // Use cached reflection to call JdkHttpUrl.wrap(uri)
        return (HttpUrl) JDK_URL_WRAP_METHOD.invoke(null, uri);
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
  @SuppressForbidden // Dynamically load JDK11+ version
  static HttpUrl.Builder newBuilder() {
    if (HttpClientFactory.isUsingJdkImplementation()) {
      if (JDK_URL_BUILDER_CONSTRUCTOR == null) {
        throw new RuntimeException("JDK HttpUrl builder not available");
      }
      try {
        // Use cached reflection to create JdkHttpUrl.JdkHttpUrlBuilder
        return (HttpUrl.Builder) JDK_URL_BUILDER_CONSTRUCTOR.newInstance();
      } catch (Exception e) {
        throw new RuntimeException("Failed to create JDK HttpUrl builder", e);
      }
    } else {
      return new OkHttpUrl.OkHttpUrlBuilder();
    }
  }
}
