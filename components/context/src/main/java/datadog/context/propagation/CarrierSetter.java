package datadog.context.propagation;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

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

  /**
   * Sets a carrier key to an ASCII value given as bytes, such as an {@link AsciiDecimal}. A setter
   * for a carrier that stores bytes can take {@code value} as it is; by default, the value is set
   * as a {@code String}. The carrier may keep {@code value}, so don't reuse it.
   *
   * @param carrier the carrier to store key/value into.
   * @param key the key to set.
   * @param value the value to set, as ASCII bytes.
   */
  default void setAscii(C carrier, String key, byte[] value) {
    set(carrier, key, new String(value, ISO_8859_1));
  }
}
