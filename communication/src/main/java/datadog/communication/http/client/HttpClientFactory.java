package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpClient;
import datadog.trace.api.InstrumenterConfig;
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

  private static final String OKHTTP = "okhttp";
  private static final String JDK = "jdk";

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
    String config = InstrumenterConfig.get().getHttpClientImplementation();
    if (config != null) {
      config = config.trim().toLowerCase();
    }

    // Force OkHttp if explicitly configured
    if (OKHTTP.equals(config)) {
      log.debug("Using OkHttp client (configured: okhttp)");
      return new OkHttpClient.OkHttpClientBuilder();
    }

    // Try JDK HttpClient if available (auto mode or explicitly configured as jdk)
    if (JdkHttpClientSupport.isAvailable()) {
      if (JDK.equals(config)) {
        log.debug("Using JDK HttpClient (configured: jdk)");
      } else {
        log.debug("Using JDK HttpClient (auto: Java 11+ detected)");
      }

      try {
        return (HttpClient.Builder) JdkHttpClientSupport.JDK_CLIENT_BUILDER_CONSTRUCTOR.newInstance();
      } catch (Exception e) {
        log.warn("Failed to instantiate JDK HttpClient builder, falling back to OkHttp", e);
      }
    } else if (JDK.equals(config)) {
      log.warn("JDK HttpClient configured but not available (Java < 11), falling back to OkHttp");
    }

    // Use OkHttp (fallback or auto on Java < 11)
    log.debug("Using OkHttp client");
    return new OkHttpClient.OkHttpClientBuilder();
  }
}
