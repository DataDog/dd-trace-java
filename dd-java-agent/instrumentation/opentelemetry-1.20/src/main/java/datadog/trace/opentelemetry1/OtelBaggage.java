package datadog.trace.opentelemetry1;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.baggage.BaggageEntry;
import io.opentelemetry.api.baggage.BaggageEntryMetadata;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class OtelBaggage implements Baggage {

  final Map<String, OtelBaggageEntry> items;

  public static OtelBaggage fromContext(AgentSpan.Context context) {
    Map<String, OtelBaggageEntry> items = new HashMap<>();
    for (Map.Entry<String, String> baggageItem : context.baggageItems()) {
      items.put(baggageItem.getKey(), new OtelBaggageEntry(baggageItem.getValue()));
    }
    return new OtelBaggage(items);
  }

  void storeInContext(AgentSpan span) {
    forEach((key, baggageEntry) -> span.setBaggageItem(key, baggageEntry.getValue()));
  }

  private OtelBaggage(Map<String, OtelBaggageEntry> items) {
    this.items = items;
  }

  @Override
  public int size() {
    return this.items.size();
  }

  @Override
  public void forEach(BiConsumer<? super String, ? super BaggageEntry> consumer) {
    this.items.forEach(consumer);
  }

  @Override
  public Map<String, BaggageEntry> asMap() {
    return Collections.unmodifiableMap(this.items);
  }

  @Nullable
  @Override
  public String getEntryValue(String entryKey) {
    OtelBaggageEntry entry = this.items.get(entryKey);
    return entry == null ? null : entry.getValue();
  }

  @Override
  public BaggageBuilder toBuilder() {
    return new OtelBaggageBuilder(new HashMap<>(this.items));
  }

  private static class OtelBaggageEntry implements BaggageEntry {
    private final String value;

    private OtelBaggageEntry(String value) {
      this.value = value;
    }

    @Override
    public String getValue() {
      return this.value;
    }

    @Override
    public BaggageEntryMetadata getMetadata() {
      return BaggageEntryMetadata.empty();
    }
  }

  private static class OtelBaggageBuilder implements BaggageBuilder {
    final Map<String, OtelBaggageEntry> items;

    private OtelBaggageBuilder() {
      this(new HashMap<>());
    }

    private OtelBaggageBuilder(Map<String, OtelBaggageEntry> items) {
      this.items = items;
    }

    @Override
    public BaggageBuilder put(String key, String value, BaggageEntryMetadata entryMetadata) {
      this.items.put(key, new OtelBaggageEntry(value));
      return this;
    }

    @Override
    public BaggageBuilder remove(String key) {
      this.items.remove(key);
      return this;
    }

    @Override
    public Baggage build() {
      return new OtelBaggage(this.items);
    }
  }
}
