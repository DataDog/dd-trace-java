package datadog.remoteconfig;

import java.io.IOException;

/**
 * This interface describes a configuration value deserializer.
 *
 * @param <T> The configuration value type.
 */
@FunctionalInterface
public interface ConfigurationDeserializer<T> {
  /**
   * Deserializes a configuration value.
   *
   * @param content The binary representation of the configuration value.
   * @return The deserialized typed configuration value.
   * @throws IOException If the configuration value cannot be deserialized.
   */
  T deserialize(byte[] content) throws IOException;
}
