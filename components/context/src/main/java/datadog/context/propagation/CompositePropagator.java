package datadog.context.propagation;

import datadog.context.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

class CompositePropagator implements Propagator {
  private final Propagator[] propagators;

  /** The last union of the propagators' keys, with the parts it was built from. */
  private volatile KeysUnion keys;

  CompositePropagator(Propagator[] propagators) {
    this.propagators = propagators;
  }

  /**
   * The union of the propagators' keys. Rebuilt only when a propagator returns a different
   * instance, which propagators do only when what their keys depend on changes.
   */
  @Override
  public CarrierKeys keys() {
    KeysUnion current = this.keys;
    if (current != null && current.isFor(this.propagators)) {
      return current.union;
    }
    KeysUnion rebuilt = new KeysUnion(this.propagators);
    this.keys = rebuilt;
    return rebuilt.union;
  }

  /** An immutable union of keys, with the instances it was built from. */
  private static final class KeysUnion {
    final CarrierKeys[] parts;
    final CarrierKeys union;

    KeysUnion(Propagator[] propagators) {
      this.parts = new CarrierKeys[propagators.length];
      CarrierKeys union = CarrierKeys.none();
      for (int i = 0; i < propagators.length; i++) {
        this.parts[i] = propagators[i].keys();
        union = union.union(this.parts[i]);
      }
      this.union = union;
    }

    boolean isFor(Propagator[] propagators) {
      for (int i = 0; i < propagators.length; i++) {
        if (propagators[i].keys() != this.parts[i]) {
          return false;
        }
      }
      return true;
    }
  }

  @Override
  public <C> void inject(Context context, C carrier, CarrierSetter<C> setter) {
    for (int i = this.propagators.length - 1; i >= 0; i--) {
      this.propagators[i].inject(context, carrier, setter);
    }
  }

  @Override
  public <C> Context extract(Context context, C carrier, CarrierVisitor<C> visitor) {
    // Extract and cache the carrier key/value pairs the propagators read
    CarrierCache carrierCache = new CarrierCache();
    visitor.forEachKeyValue(carrier, keys(), carrierCache);
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
