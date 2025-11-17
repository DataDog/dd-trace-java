package datadog.opentelemetry.shim.baggage;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.baggage.BaggageEntryMetadata;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class OtelBaggageBuilder implements BaggageBuilder {
  private final Map<String, String> items;

  public OtelBaggageBuilder(Map<String, String> items) {
    this.items = new HashMap<>(items);
  }

  @Override
  public BaggageBuilder put(
      @Nullable String key, @Nullable String value, BaggageEntryMetadata ignore) {
    if (key != null && value != null) {
      items.put(key, value);
    }
    return this;
  }

  @Override
  public BaggageBuilder remove(@Nullable String key) {
    if (key != null) {
      items.remove(key);
    }
    return this;
  }

  @Override
  public Baggage build() {
    return new OtelBaggage(items);
  }
}
