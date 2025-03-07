package datadog.remoteconfig;

import java.io.IOException;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * This interface describes a configuration value change for maps. For strongly-typed value
 * notification, check {@link ConfigurationChangesTypedListener}.
 */
@FunctionalInterface
public interface ConfigurationMapListener {
  /**
   * Notifies a new configuration value change.
   *
   * @param configKey The configuration key that changed.
   * @param content The new configuration value, might be {@code null} to "unapply" the
   *     configuration.
   * @param pollingRateHinter The callback to hint about the expected polling rate.
   * @throws IOException If the configuration could not be deserialized.
   */
  void accept(
      String configKey, @Nullable Map<String, Object> content, PollingRateHinter pollingRateHinter)
      throws IOException;
}
