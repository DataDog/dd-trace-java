package datadog.trace.bootstrap.instrumentation.api;

import static datadog.context.ContextKey.named;
import static java.util.Collections.unmodifiableMap;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ImplicitContextKeyed;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/** Baggage are key/value store which propagate alongside {@link Context}. */
public class Baggage implements ImplicitContextKeyed {
  private static final ContextKey<Baggage> CONTEXT_KEY = named("baggage-key");
  private final Map<String, String> items;

  /**
   * The <a href="https://www.w3.org/TR/baggage/">W3C Baggage header representation</a> of the
   * baggage instance, {@code null} if not in sync with the current baggage items.
   */
  private String w3cHeader;

  /**
   * Gets baggage from context.
   *
   * @param context The context to get baggage from.
   * @return The baggage from the given context if any, {@code null} if none.
   */
  public static @Nullable Baggage fromContext(Context context) {
    return context.get(CONTEXT_KEY);
  }

  /**
   * Create empty baggage.
   *
   * @return Empty baggage.
   */
  public static Baggage empty() {
    return create(new HashMap<>(), "");
  }

  /**
   * Create baggage from items.
   *
   * @param items The original baggage items.
   * @return Baggage with the given items.
   */
  public static Baggage create(Map<String, String> items) {
    return new Baggage(items, null);
  }

  /**
   * Create baggage from items.
   *
   * @param items The original baggage items.
   * @param w3cHeader The W3C Baggage header representation.
   * @return Baggage with the given items and its W3C header cached.
   */
  public static Baggage create(Map<String, String> items, String w3cHeader) {
    return new Baggage(items, w3cHeader);
  }

  private Baggage(Map<String, String> items, String w3cHeader) {
    this.items = items;
    this.w3cHeader = w3cHeader;
  }

  /**
   * Adds a baggage item.
   *
   * @param key The item key.
   * @param value The item value.
   */
  public void addItem(String key, String value) {
    this.items.put(key, value);
    this.w3cHeader = null;
  }

  /**
   * Removes a baggage item.
   *
   * @param key The item key to remove.
   */
  public void removeItem(String key) {
    if (this.items.remove(key) != null) {
      this.w3cHeader = null;
    }
  }

  /**
   * Gets the <a href="https://www.w3.org/TR/baggage/">W3C Baggage header representation</a>.
   *
   * @return The header value, {@code null} if not in sync with the current baggage items.
   */
  public @Nullable String getW3cHeader() {
    return this.w3cHeader;
  }

  /**
   * Updates the <a href="https://www.w3.org/TR/baggage/">W3C Baggage header representation</a>.
   *
   * @param w3cHeader The header value.
   */
  public void setW3cHeader(String w3cHeader) {
    this.w3cHeader = w3cHeader;
  }

  /**
   * Gets a view of the baggage items.
   *
   * @return The read-only view of the baggage items.
   */
  public Map<String, String> asMap() {
    return unmodifiableMap(this.items);
  }

  @Override
  public Context storeInto(@Nonnull Context context) {
    return context.with(CONTEXT_KEY, this);
  }
}
