package datadog.context.propagation;

import datadog.context.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

class CompositePropagator implements Propagator {
  private final Propagator[] propagators;

  CompositePropagator(Propagator[] propagators) {
    this.propagators = propagators;
  }

  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    for (int i = this.propagators.length - 1; i >= 0; i--) {
      this.propagators[i].inject(context, carrier, setter);
    }
  }

  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    // Extract and cache carrier key/value pairs
    CarrierCache carrierCache = new CarrierCache();
    visitor.forEachKeyValue(carrier, carrierCache);
    // Run the multiple extractions on cache
    for (Propagator propagator : this.propagators) {
      context = propagator.extract(context, carrierCache, carrierCache);
    }
    return context;
  }

  static class CarrierCache implements BiConsumer<String, String>, CarrierVisitor<CarrierCache> {
    /** Cached key/values from carrier (even indexes are keys, odd indexes are values). */
    private final List<String> keysAndValues;

    public CarrierCache() {
      this.keysAndValues = new ArrayList<>(32);
    }

    @Override
    public void accept(String key, String value) {
      this.keysAndValues.add(key);
      this.keysAndValues.add(value);
    }

    @Override
    public void forEachKeyValue(CarrierCache carrier, BiConsumer<String, String> visitor) {
      for (int i = 0; i < carrier.keysAndValues.size() - 1; i += 2) {
        visitor.accept(carrier.keysAndValues.get(i), carrier.keysAndValues.get(i + 1));
      }
    }
  }
}
