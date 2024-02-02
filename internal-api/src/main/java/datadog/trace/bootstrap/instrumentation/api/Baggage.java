package datadog.trace.bootstrap.instrumentation.api;

import java.util.Map;
import java.util.function.BiConsumer;

/** Immutable key-value store for contextual information shared between spans. */
public interface Baggage extends ImplicitContextKeyed {

  /** Returns the item associated with the key; {@code null} if there's no value. */
  String get(String key);

  /** Iterates over the items in this baggage. */
  void forEach(BiConsumer<String, String> consumer);

  /** Returns a read-only {@link Map} view of this baggage. */
  Map<String, String> asMap();

  /** Returns the number of baggage items. */
  int size();

  /** Returns whether this baggage contains any items. */
  default boolean isEmpty() {
    return size() == 0;
  }

  @Override
  default ScopedContext storeInto(ScopedContext context) {
    return context.with(ScopedContextKey.BAGGAGE_KEY, this);
  }

  /** Returns a builder that includes all the items in this baggage. */
  Builder toBuilder();

  /** A mutable builder of {@link Baggage}. */
  interface Builder {

    /** Adds a new item to the baggage. */
    Builder put(String key, String value);

    /** Removes an item from the baggage. */
    Builder remove(String key);

    /** Creates immutable {@link Baggage} containing the items currently in this builder. */
    Baggage build();
  }
}
