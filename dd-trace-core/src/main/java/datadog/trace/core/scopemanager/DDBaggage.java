package datadog.trace.core.scopemanager;

import static java.util.Collections.emptyMap;

import datadog.trace.api.Baggage;
import java.util.HashMap;
import java.util.Map;

// TODO Javadoc
public class DDBaggage implements Baggage {
  private static final Baggage EMPTY = new DDBaggage(emptyMap());

  private final Map<String, String> items;

  public static Baggage empty() {
    return EMPTY;
  }

  public static BaggageBuilder builder() {
    return new DDBaggageBuilder(emptyMap());
  }

  private DDBaggage(Map<String, String> items) {
    this.items = items;
  }

  @Override
  public String getItemValue(String key) {
    return null;
  }

  @Override
  public Map<String, String> getItems() {
    return null;
  }

  @Override
  public BaggageBuilder toBuilder() {
    return new DDBaggageBuilder(this.items);
  }

  // TODO Javadoc
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
      return new DDBaggage(this.items);
    }
  }
}
