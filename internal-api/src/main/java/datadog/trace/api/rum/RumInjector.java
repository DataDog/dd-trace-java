package datadog.trace.api.rum;

import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.function.Function;

public final class RumInjector {
  private static volatile boolean initialized = false;
  private static volatile boolean enabled;
  private static volatile String snippet;

  private static final String MARKER = "</head>";
  private static final DDCache<String, byte[]> SNIPPET_CACHE = DDCaches.newFixedSizeCache(16);
  private static final DDCache<String, byte[]> MARKER_CACHE = DDCaches.newFixedSizeCache(16);
  private static final Function<String, byte[]> SNIPPET_ADDER =
      charset -> {
        try {
          return snippet.getBytes(charset);
        } catch (Throwable t) {
          return null;
        }
      };
  private static final Function<String, byte[]> MARKER_ADDER =
      charset -> {
        try {
          return MARKER.getBytes(charset);
        } catch (Throwable t) {
          return null;
        }
      };

  /**
   * Check whether RUM injection is enabled and ready to inject.
   *
   * @return {@code true} if enabled, {@code otherwise}.
   */
  public static boolean isEnabled() {
    if (!initialized) {
      Config config = Config.get();
      boolean rumEnabled = config.isRumEnabled();
      RumInjectorConfig injectorConfig = config.getRumInjectorConfig();
      if (rumEnabled && injectorConfig != null) {
        enabled = true;
        snippet = injectorConfig.getSnippet();
      } else {
        enabled = false;
        snippet = null;
      }
      initialized = true;
    }
    return enabled;
  }

  /**
   * Get the HTML snippet to inject RUM SDK
   *
   * @return The HTML snippet to inject, {@code null} if RUM injection is disabled to inject.
   */
  public static byte[] getSnippet(String encoding) {
    if (!isEnabled()) {
      return null;
    }
    return SNIPPET_CACHE.computeIfAbsent(encoding, SNIPPET_ADDER);
  }

  public static byte[] getMarker(String encoding) {
    if (!isEnabled()) {
      return null;
    }
    return MARKER_CACHE.computeIfAbsent(encoding, MARKER_ADDER);
  }
}
