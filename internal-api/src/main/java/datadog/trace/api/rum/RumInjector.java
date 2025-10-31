package datadog.trace.api.rum;

import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.function.Function;
import javax.annotation.Nullable;

@SuppressFBWarnings(
    value = "SING_SINGLETON_HAS_NONPRIVATE_CONSTRUCTOR",
    justification = "Usage in tests")
public final class RumInjector {
  private static final RumInjector INSTANCE =
      new RumInjector(Config.get(), InstrumenterConfig.get());
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

  private static volatile RumTelemetryCollector telemetryCollector = RumTelemetryCollector.NO_OP;

  RumInjector(Config config, InstrumenterConfig instrumenterConfig) {
    boolean rumEnabled = instrumenterConfig.isRumEnabled();
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

  /** Starts telemetry collection if RUM injection is enabled. */
  public static void enableTelemetry() {
    if (INSTANCE.isEnabled()) {
      telemetryCollector = new RumInjectorMetrics();
      telemetryCollector.onInitializationSucceed();
    } else {
      telemetryCollector = RumTelemetryCollector.NO_OP;
    }
  }

  /** Shuts down telemetry collection and resets the telemetry collector to NO_OP. */
  public static void shutdownTelemetry() {
    telemetryCollector.close();
    telemetryCollector = RumTelemetryCollector.NO_OP;
  }

  /**
   * Sets the telemetry collector. This is used for testing purposes only.
   *
   * @param collector The telemetry collector to set or {@code null} to reset to NO_OP.
   */
  public static void setTelemetryCollector(RumTelemetryCollector collector) {
    telemetryCollector = collector != null ? collector : RumTelemetryCollector.NO_OP;
  }

  /**
   * Gets the telemetry collector.
   *
   * @return The telemetry collector used to report telemetry.
   */
  public static RumTelemetryCollector getTelemetryCollector() {
    return telemetryCollector;
  }
}
