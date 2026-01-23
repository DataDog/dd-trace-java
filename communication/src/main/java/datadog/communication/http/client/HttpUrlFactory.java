package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpUrl;
import de.thetaphi.forbiddenapis.SuppressForbidden;
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
  @SuppressForbidden // Dynamically load JDK11+ version
  static HttpUrl parse(String url) {
    Objects.requireNonNull(url, "url");

    // Use JDK HttpClient implementation if available
    if (JdkHttpClientSupport.isAvailable()) {
      try {
        URI uri = new URI(url);
        // Use cached reflection to call JdkHttpUrl.wrap(uri)
        return (HttpUrl) JdkHttpClientSupport.JDK_URL_WRAP_METHOD.invoke(null, uri);
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
    if (JdkHttpClientSupport.isAvailable()) {
      try {
        // Use cached reflection to create JdkHttpUrl.JdkHttpUrlBuilder
        return (HttpUrl.Builder) JdkHttpClientSupport.JDK_URL_BUILDER_CONSTRUCTOR.newInstance();
      } catch (Exception e) {
        throw new RuntimeException("Failed to create JDK HttpUrl builder", e);
      }
    } else {
      return new OkHttpUrl.OkHttpUrlBuilder();
    }
  }
}
