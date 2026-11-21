package datadog.context.propagation;

import datadog.context.Context;
import java.util.Arrays;
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
    CarrierKeys keys = keys();
    CarrierCache carrierCache = new CarrierCache(keys);
    visitor.forEachKeyValue(carrier, keys, carrierCache);
    // Run the multiple extractions on cache
    for (Propagator propagator : this.propagators) {
      context = propagator.extract(context, carrierCache, carrierCache);
    }
    return context;
  }

  /**
   * The carrier key/value pairs the propagators read, cached so each propagator reads them without
   * walking the carrier again, and readable in place as an {@link IndexedCarrier}.
   */
  static final class CarrierCache
      implements BiConsumer<String, String>, CarrierVisitor<CarrierCache>, IndexedCarrier {
    /** Enough for the trace headers a carrier usually holds once unread keys are skipped. */
    private static final int INITIAL_PAIRS = 8;

    /** Keys at even indexes, values at odd ones. */
    private String[] keysAndValues = new String[2 * INITIAL_PAIRS];

    /** Each pair's {@link HeaderSchema} constant, or null. */
    private HeaderSchema[] headers = new HeaderSchema[INITIAL_PAIRS];

    private int size;

    /** Classifies keys with {@code keys}, which knows each declared name's constant. */
    private final CarrierKeys keys;

    CarrierCache(CarrierKeys keys) {
      this.keys = keys;
    }

    CarrierCache() {
      this(CarrierKeys.none());
    }

    @Override
    public void accept(String key, String value) {
      int index = 2 * this.size;
      if (index == this.keysAndValues.length) {
        this.keysAndValues = Arrays.copyOf(this.keysAndValues, 2 * index);
        this.headers = Arrays.copyOf(this.headers, this.size * 2);
      }
      this.keysAndValues[index] = key;
      this.keysAndValues[index + 1] = value;
      this.headers[this.size] = key == null ? null : this.keys.header(key);
      this.size++;
    }

    @Override
    public void forEachKeyValue(CarrierCache carrier, BiConsumer<String, String> visitor) {
      for (int i = 0; i < carrier.size; i++) {
        visitor.accept(carrier.keysAndValues[2 * i], carrier.keysAndValues[2 * i + 1]);
      }
    }

    @Override
    public int size() {
      return this.size;
    }

    @Override
    public String key(int index) {
      return this.keysAndValues[2 * index];
    }

    @Override
    public String value(int index) {
      return this.keysAndValues[2 * index + 1];
    }

    @Override
    public HeaderSchema header(int index) {
      return this.headers[index];
    }
  }
}
