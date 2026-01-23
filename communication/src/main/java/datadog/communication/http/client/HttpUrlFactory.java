package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpUrl;
import java.util.Objects;

/**
 * Factory for creating HttpUrl instances. This factory selects the appropriate implementation
 * based on configuration and Java version.
 *
 * <p>For now, this uses OkHttp implementation. In Phase 2, Task 2.3, this will be enhanced
 * to support JDK HttpClient based on Java version detection and configuration.
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
    okhttp3.HttpUrl okHttpUrl = okhttp3.HttpUrl.parse(url);
    if (okHttpUrl == null) {
      throw new IllegalArgumentException("Invalid URL: " + url);
    }
    return OkHttpUrl.wrap(okHttpUrl);
  }

  /**
   * Creates a new builder for constructing URLs.
   *
   * @return a new Builder
   */
  static HttpUrl.Builder newBuilder() {
    return new OkHttpUrl.OkHttpUrlBuilder();
  }
}
