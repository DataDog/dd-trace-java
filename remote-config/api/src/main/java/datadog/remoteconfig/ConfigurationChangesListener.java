package datadog.remoteconfig;

import java.io.IOException;
import javax.annotation.Nullable;

/**
 * This interface describes a configuration value change. For strongly-typed value notification,
 * check {@link ConfigurationChangesTypedListener}.
 */
@FunctionalInterface
public interface ConfigurationChangesListener {
  /**
   * Notifies a new configuration value change.
   *
   * @param configKey The configuration key that changed.
   * @param content The new configuration value, might be {@code null} to "unapply" the
   *     configuration.
   * @param pollingRateHinter The callback to hint about the expected polling rate.
   * @throws IOException If the configuration could not be deserialized.
   */
  void accept(String configKey, @Nullable byte[] content, PollingRateHinter pollingRateHinter)
      throws IOException;
}
