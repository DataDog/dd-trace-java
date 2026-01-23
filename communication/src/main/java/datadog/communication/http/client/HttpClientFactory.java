package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpClient;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating HttpClient instances. This factory selects the appropriate
 * implementation based on configuration and Java version.
 *
 * <p>Configuration is controlled via {@code dd.http.client.implementation} property
 * with values:
 * <ul>
 *   <li>{@code auto} (default): Use JDK HttpClient on Java 11+, OkHttp otherwise</li>
 *   <li>{@code okhttp}: Force OkHttp implementation</li>
 *   <li>{@code jdk}: Force JDK HttpClient (fails on Java < 11)</li>
 * </ul>
 *
 * <p>In Phase 2, only OkHttp implementation is available. JDK HttpClient support
 * will be added in Phase 4.
 */
final class HttpClientFactory {
  private static final Logger log = LoggerFactory.getLogger(HttpClientFactory.class);

  private HttpClientFactory() {
    // Utility class
  }

  /**
   * Creates a new builder for constructing HTTP clients.
   * Selects implementation based on configuration and Java version.
   *
   * @return a new Builder
   */
  @SuppressForbidden // Dynamically load JDK11+ version
  static HttpClient.Builder newBuilder() {
    // Try JDK HttpClient implementation if available
    if (JdkHttpClientSupport.isAvailable()) {
      try {
        return (HttpClient.Builder) JdkHttpClientSupport.JDK_CLIENT_BUILDER_CONSTRUCTOR.newInstance();
      } catch (Exception e) {
        log.debug("Failed to instantiate JDK HttpClient builder, falling back to OkHttp", e);
      }
    }
    return new OkHttpClient.OkHttpClientBuilder();
  }
}
