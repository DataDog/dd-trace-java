package datadog.trace.core;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;

import datadog.trace.bootstrap.instrumentation.api.Baggage;
import java.util.HashMap;
import java.util.Map;

/** Core {@link Baggage} implementation. */
public class DDBaggage implements Baggage {
  private static final Baggage EMPTY = new DDBaggage(emptyMap());

  // TODO Implement using String[] for performance?
  private final Map<String, String> items;

  private DDBaggage(Map<String, String> items) {
    this.items = items;
  }

  /**
   * Get an empty {@link Baggage} instance.
   *
   * @return An empty {@link Baggage} instance.
   */
  public static Baggage empty() {
    return EMPTY;
  }

  /**
   * Get an empty {@link Baggage.BaggageBuilder}.
   *
   * @return An empty {@link Baggage.BaggageBuilder}.
   */
  public static BaggageBuilder builder() {
    return new DDBaggageBuilder(emptyMap());
  }

  @Override
  public String getItemValue(String key) {
    return this.items.get(key);
  }

  @Override
  public Map<String, String> asMap() {
    return unmodifiableMap(this.items);
  }

  @Override
  public int size() {
    return this.items.size();
  }

  @Override
  public BaggageBuilder toBuilder() {
    return new DDBaggageBuilder(this.items);
  }

  private static class DDBaggageBuilder implements BaggageBuilder {
    private final Map<String, String> items;

    private DDBaggageBuilder(Map<String, String> items) {
      this.items = new HashMap<>(items);
    }

    @Override
    public BaggageBuilder put(String key, String value) {
      this.items.put(key, value);
      return this;
    }

    @Override
    public BaggageBuilder remove(String key) {
      this.items.remove(key);
      return this;
    }

    @Override
    public Baggage build() {
      return new DDBaggage(new HashMap<>(this.items));
    }
  }
}
