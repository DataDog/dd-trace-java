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

  /**
   * Sets a carrier key to an ASCII value built as bytes. A setter for a carrier that stores bytes
   * can take {@link AsciiValueBuilder#toBytes()} directly; by default, the value is set as a {@code
   * String}.
   *
   * @param carrier the carrier to store key/value into.
   * @param key the key to set.
   * @param value the value to set.
   */
  default void setAscii(C carrier, String key, AsciiValueBuilder value) {
    set(carrier, key, value.toString());
  }
}
