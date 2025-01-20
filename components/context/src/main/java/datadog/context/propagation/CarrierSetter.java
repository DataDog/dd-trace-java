package datadog.context.propagation;

import javax.annotation.Nullable;

@FunctionalInterface
public interface CarrierSetter<C> {
  /**
   * Sets a carrier key/value pair.
   *
   * @param carrier the carrier to store key/value into.
   * @param key the key to set.
   * @param value the value to set.
   */
  void set(@Nullable C carrier, String key, String value);
}
