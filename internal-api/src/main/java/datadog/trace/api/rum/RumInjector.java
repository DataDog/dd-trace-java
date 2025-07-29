package datadog.trace.api.rum;

import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.function.Function;
import javax.annotation.Nullable;

public final class RumInjector {
  private static final RumInjector INSTANCE = new RumInjector(Config.get());
  private static final String MARKER = "</head>";
  private static final char[] MARKER_CHARS = MARKER.toCharArray();
  private static final Function<String, byte[]> MARKER_BYTES =
      charset -> {
        try {
          return MARKER.getBytes(charset);
        } catch (Throwable t) {
          return null;
        }
      };

  private final boolean enabled;
  private final String snippet;
  private final char[] snippetChars;

  private final DDCache<String, byte[]> snippetCache;
  private final DDCache<String, byte[]> markerCache;
  private final Function<String, byte[]> snippetBytes;

  RumInjector(Config config) {
    boolean rumEnabled = config.isRumEnabled();
    RumInjectorConfig injectorConfig = config.getRumInjectorConfig();
    // If both RUM is enabled and injector config is valid
    if (rumEnabled && injectorConfig != null) {
      this.enabled = true;
      this.snippet = injectorConfig.getSnippet();
      this.snippetCache = DDCaches.newFixedSizeCache(16);
      this.markerCache = DDCaches.newFixedSizeCache(16);
      this.snippetChars = this.snippet.toCharArray();
      this.snippetBytes =
          charset -> {
            try {
              return snippet.getBytes(charset);
            } catch (Throwable t) {
              return null;
            }
          };
    } else {
      this.enabled = false;
      this.snippet = null;
      this.snippetCache = null;
      this.markerCache = null;
      this.snippetBytes = null;
      this.snippetChars = null;
    }
  }

  public static RumInjector get() {
    return INSTANCE;
  }

  /**
   * Checks whether RUM injection is enabled and ready to inject.
   *
   * @return {@code true} if enabled, {@code otherwise}.
   */
  public boolean isEnabled() {
    return this.enabled;
  }

  /**
   * Gets the HTML snippet to inject RUM SDK
   *
   * @return The HTML snippet chars to inject, {@code null} if RUM injection is disabled.
   */
  @Nullable
  public char[] getSnippetChars() {
    if (!this.enabled) {
      return null;
    }
    return this.snippetChars;
  }

  /**
   * Gets the HTML snippet to inject RUM SDK
   *
   * @return The HTML snippet to inject, {@code null} if RUM injection is disabled.
   */
  @Nullable
  public byte[] getSnippetBytes(String encoding) {
    if (!this.enabled) {
      return null;
    }
    return this.snippetCache.computeIfAbsent(encoding, this.snippetBytes);
  }

  /**
   * Gets the marker chars to inject RUM SDK after.
   *
   * @return The marker chars, {@code null} if RUM injection is disabled.
   */
  @Nullable
  public char[] getMarkerChars() {
    if (!this.enabled) {
      return null;
    }
    return MARKER_CHARS;
  }

  /**
   * Gets the marker bytes to inject RUM SDK after.
   *
   * @param encoding The encoding to get the marker bytes from.
   * @return The marker bytes, {@code null} if RUM injection is disabled.
   */
  @Nullable
  public byte[] getMarkerBytes(String encoding) {
    if (!this.enabled) {
      return null;
    }
    return this.markerCache.computeIfAbsent(encoding, MARKER_BYTES);
  }
}
