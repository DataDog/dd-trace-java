package datadog.http.client;

import de.thetaphi.forbiddenapis.SuppressForbidden;

/**
 * Static factory class for obtaining HTTP provider implementations.
 *
 * <p>This class provides a singleton access point to an {@link HttpProvider} instance, which serves
 * as a factory for creating HTTP client components such as clients, requests, URLs, and request
 * bodies.
 *
 * <p>The provider selection follows a hierarchical fallback strategy: - First attempts to load the
 * JDK-based HTTP provider implementation - Falls back to the OkHttp-based provider if the JDK
 * version is not available or incompatible - Can be forced into compatibility mode to skip the JDK
 * provider and use OkHttp directly
 *
 * <p>The selected provider is cached after the first access for performance. This class is
 * thread-safe and all methods can be safely called from multiple threads.
 */
public final class HttpProviders {
  private static final String JDK_HTTP_PROVIDER_CLASS_NAME =
      "datadog.http.client.jdk.JdkHttpProvider";
  private static final String OKHTTP_PROVIDER_CLASS_NAME =
      "datadog.http.client.okhttp.OkHttpProvider";
  private static volatile boolean compatibilityMode = false;
  private static HttpProvider provider;

  private HttpProviders() {}

  public static void forceCompatClient() {
    // Skip if already in compat mode
    if (compatibilityMode) {
      return;
    }
    compatibilityMode = true;
    provider = null;
  }

  public static HttpProvider get() {
    if (provider == null) {
      provider = findProvider();
    }
    return provider;
  }

  @SuppressForbidden // Class#forName(String) used to dynamically load the http API implementation
  private static HttpProvider findProvider() {
    Class<?> clazz = null;
    // Load the default client class
    if (!compatibilityMode) {
      try {
        clazz = Class.forName(JDK_HTTP_PROVIDER_CLASS_NAME);
      } catch (ClassNotFoundException | UnsupportedClassVersionError ignored) {
        compatibilityMode = true;
      }
    }
    // If not loaded, load the compat client class
    if (clazz == null) {
      try {
        clazz = Class.forName(OKHTTP_PROVIDER_CLASS_NAME);
      } catch (ClassNotFoundException ignored) {
      }
    }
    // If no class loaded, raise the illegal state
    if (clazz == null) {
      throw new IllegalStateException("No http client implementation found");
    }
    try {
      return (HttpProvider) clazz.getDeclaredConstructor().newInstance();
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("No http client implementation found", e);
    }
  }
}
