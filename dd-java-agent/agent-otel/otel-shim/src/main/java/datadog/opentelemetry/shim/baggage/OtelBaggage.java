package datadog.opentelemetry.shim.baggage;

import static java.util.stream.Collectors.toMap;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.baggage.BaggageEntry;
import io.opentelemetry.api.baggage.BaggageEntryMetadata;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class OtelBaggage implements Baggage {
  private final datadog.trace.bootstrap.instrumentation.api.Baggage delegate;

  public OtelBaggage(datadog.trace.bootstrap.instrumentation.api.Baggage delegate) {
    this.delegate = delegate;
  }

  public OtelBaggage(Map<String, String> items) {
    this(datadog.trace.bootstrap.instrumentation.api.Baggage.create(items));
  }

  @Override
  public int size() {
    return delegate.asMap().size();
  }

  @Override
  public void forEach(BiConsumer<? super String, ? super BaggageEntry> consumer) {
    for (Map.Entry<String, String> entry : delegate.asMap().entrySet()) {
      consumer.accept(entry.getKey(), new ValueOnly(entry));
    }
  }

  @Override
  public Map<String, BaggageEntry> asMap() {
    return delegate.asMap().entrySet().stream().collect(toMap(Map.Entry::getKey, ValueOnly::new));
  }

  @Nullable
  @Override
  public String getEntryValue(String key) {
    return delegate.asMap().get(key);
  }

  @Nullable
  @Override
  public BaggageEntry getEntry(String key) {
    String value = getEntryValue(key);
    return value != null ? new ValueOnly(value) : null;
  }

  @Override
  public BaggageBuilder toBuilder() {
    return new OtelBaggageBuilder(delegate.asMap());
  }

  public datadog.trace.bootstrap.instrumentation.api.Baggage asAgentBaggage() {
    return delegate;
  }

  static class ValueOnly implements BaggageEntry {
    private final String value;

    ValueOnly(String value) {
      this.value = value;
    }

    ValueOnly(Map.Entry<String, String> entry) {
      this(entry.getValue());
    }

    @Override
    public String getValue() {
      return value;
    }

    @Override
    public BaggageEntryMetadata getMetadata() {
      return BaggageEntryMetadata.empty();
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(value);
    }

    @Override
    public final boolean equals(Object o) {
      return (o instanceof ValueOnly) && Objects.equals(value, ((ValueOnly) o).value);
    }
  }
}
