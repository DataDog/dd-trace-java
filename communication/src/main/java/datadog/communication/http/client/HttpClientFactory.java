package datadog.communication.http.client;

import datadog.communication.http.okhttp.OkHttpClient;
import datadog.environment.JavaVirtualMachine;
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

  private static final String CONFIG_PROPERTY = "dd.http.client.implementation";
  private static final String AUTO = "auto";
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
  static HttpClient.Builder newBuilder() {
    String implementation = getConfiguredImplementation();

    switch (implementation.toLowerCase()) {
      case OKHTTP:
        log.debug("Using OkHttp client (configured: okhttp)");
        return createOkHttpBuilder();

      case JDK:
        if (!JavaVirtualMachine.isJavaVersionAtLeast(11)) {
          log.warn("JDK HttpClient requires Java 11+, falling back to OkHttp");
          return createOkHttpBuilder();
        }
        log.debug("Using JDK HttpClient (configured: jdk)");
        return createJdkBuilder();

      case AUTO:
      default:
        return selectImplementationAutomatically();
    }
  }

  private static String getConfiguredImplementation() {
    String value = InstrumenterConfig.get().getHttpClientImplementation();
    if (value == null || value.trim().isEmpty()) {
      return AUTO;
    }
    String normalized = value.trim().toLowerCase();
    if (!normalized.equals(AUTO) && !normalized.equals(OKHTTP) && !normalized.equals(JDK)) {
      log.warn("Invalid value for {}: '{}', defaulting to 'auto'", CONFIG_PROPERTY, value);
      return AUTO;
    }
    return normalized;
  }

  private static HttpClient.Builder selectImplementationAutomatically() {
    if (JavaVirtualMachine.isJavaVersionAtLeast(11)) {
      log.debug("Java 11+ detected, using JDK HttpClient");
      return createJdkBuilder();
    } else {
      log.debug("Java 8-10 detected, using OkHttp client");
      return createOkHttpBuilder();
    }
  }

  private static HttpClient.Builder createOkHttpBuilder() {
    return new OkHttpClient.OkHttpClientBuilder();
  }

  @SuppressForbidden // Dynamically load JDK11+ version
  private static HttpClient.Builder createJdkBuilder() {
    if (!JdkHttpClientSupport.isAvailable()) {
      log.warn("JDK HttpClient not available, falling back to OkHttp");
      return new OkHttpClient.OkHttpClientBuilder();
    }

    try {
      // Use cached reflection to instantiate JDK HttpClient builder
      return (HttpClient.Builder) JdkHttpClientSupport.JDK_CLIENT_BUILDER_CONSTRUCTOR.newInstance();
    } catch (Exception e) {
      log.warn("Failed to instantiate JDK HttpClient builder, falling back to OkHttp", e);
      return new OkHttpClient.OkHttpClientBuilder();
    }
  }
}
