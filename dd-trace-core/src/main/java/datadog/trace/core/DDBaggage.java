package datadog.trace.core;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;

import datadog.trace.bootstrap.instrumentation.api.Baggage;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Map-based {@link Baggage} implementation. */
public final class DDBaggage implements Baggage {
  private static final Baggage EMPTY = new DDBaggage(emptyMap());

  private final Map<String, String> items;

  private DDBaggage(Map<String, String> items) {
    this.items = items;
  }

  public static Baggage empty() {
    return EMPTY;
  }

  public static Baggage.Builder builder() {
    return new DDBaggageBuilder(emptyMap());
  }

  @Override
  public String get(String key) {
    return items.get(key);
  }

  @Override
  public void forEach(BiConsumer<String, String> consumer) {
    items.forEach(consumer);
  }

  @Override
  public Map<String, String> asMap() {
    return unmodifiableMap(items);
  }

  @Override
  public int size() {
    return items.size();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    DDBaggage that = (DDBaggage) o;
    return items.equals(that.items);
  }

  @Override
  public int hashCode() {
    return items.hashCode();
  }

  @Override
  public Builder toBuilder() {
    return new DDBaggageBuilder(items);
  }

  private static class DDBaggageBuilder implements Baggage.Builder {
    private final Map<String, String> items;

    DDBaggageBuilder(Map<String, String> items) {
      this.items = new HashMap<>(items);
    }

    @Override
    public Baggage.Builder put(String key, String value) {
      this.items.put(key, value);
      return this;
    }

    @Override
    public Baggage.Builder remove(String key) {
      this.items.remove(key);
      return this;
    }

    @Override
    public Baggage build() {
      return items.isEmpty() ? empty() : new DDBaggage(new HashMap<>(this.items));
    }
  }
}
