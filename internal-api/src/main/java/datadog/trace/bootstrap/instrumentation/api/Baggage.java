package datadog.trace.bootstrap.instrumentation.api;

import java.util.Map;

/**
 * {@link Baggage} is an immutable key-value store for contextual information shared between spans.
 */
public interface Baggage {
  /**
   * Get baggage item value from its key.
   *
   * @param key The baggage item key to get the value.
   * @return The baggage item value, <code>null</code> if no baggage with the given key.
   */
  String getItemValue(String key);

  /**
   * Get the baggage items as map.
   *
   * @return An immutable map representing baggage items.
   */
  Map<String, String> asMap();

  /**
   * Get the baggage item count.
   *
   * @return The baggage item count.
   */
  int size();

  /**
   * Check whether the baggage is empty.
   *
   * @return <code>true</code> if the baggage has no item, <code>false</code> otherwise.
   */
  default boolean isEmpty() {
    return size() == 0;
  }

  /**
   * Create a {@link BaggageBuilder} with all the items of the {@link Baggage} instance.
   *
   * @return A {@link BaggageBuilder} with all the items of this instance.
   */
  BaggageBuilder toBuilder();

  /**
   * Builder to create immutable {@link Baggage}. To update an existing baggage (into a new
   * instance), check {@link Baggage#toBuilder()}.
   */
  interface BaggageBuilder {
    /**
     * Append a baggage item. Replace the existing value if the key already exists.
     *
     * @param key The baggage item key.
     * @param value The baggage item value.
     * @return This instance.
     */
    BaggageBuilder put(String key, String value);

    /**
     * Remove a baggage item. Do nothing if the given key is no value.
     *
     * @param key The key of the baggage item to remove.
     * @return This instance.
     */
    BaggageBuilder remove(String key);

    /**
     * Build the final baggage instance.
     *
     * @return The baggage instance.
     */
    Baggage build();
  }
}
