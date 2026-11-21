package datadog.context.propagation;

import java.util.function.BiConsumer;

/**
 * This interface represents the capacity of walking through a carrier content, iterating over its
 * key/value pairs.
 *
 * <p>Walking through carrier is preferred to direct access to carrier key/value pairs as some
 * carrier implementations do not have built-in direct access and require walking over the full
 * carrier structure to find the requested key/value pair, leading to multiple walks when multiple
 * keys are requested, whereas the visitor is expected to walk through only once, and the
 * propagators to keep the data they need using the visitor callback.
 *
 * @param <C> the type of carrier.
 */
@FunctionalInterface
public interface CarrierVisitor<C> {
  /**
   * Iterates over the carrier content and calls the visitor callback for every key/value found.
   *
   * @param carrier the carrier to iterate over.
   * @param visitor the callback to call for each carrier key/value pair found.
   */
  void forEachKeyValue(C carrier, BiConsumer<String, String> visitor);

  /**
   * Iterates over the carrier content, calling the visitor callback for at least every key/value
   * whose key is in {@code keys}. Visitors that can skip a key cheaply (for instance, before
   * decoding its value) override this to skip the keys not in {@code keys}; the default visits
   * every key, as {@link #forEachKeyValue(Object, BiConsumer)} does.
   *
   * @param carrier the carrier to iterate over.
   * @param keys the keys the caller reads; others may be skipped.
   * @param visitor the callback to call for each carrier key/value pair found.
   */
  default void forEachKeyValue(C carrier, CarrierKeys keys, BiConsumer<String, String> visitor) {
    forEachKeyValue(carrier, visitor);
  }
}
