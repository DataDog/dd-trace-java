package datadog.context.propagation;

@FunctionalInterface
public interface CarrierSetter<C> {
  /**
   * Sets a carrier key/value pair.
   *
   * @param carrier the carrier to store key/value into.
   * @param key the key to set.
   * @param value the value to set.
   */
  void set(C carrier, String key, String value);
}
