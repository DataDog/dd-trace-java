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
      implements BiConsumer<String, String>,
          TransientValueConsumer,
          CarrierVisitor<CarrierCache>,
          IndexedCarrier {
    /** Enough for the trace headers a carrier usually holds once unread keys are skipped. */
    private static final int INITIAL_PAIRS = 8;

    /**
     * Keys at even indexes, values at odd ones. A value is a {@code String}, or a transient view
     * (handed through {@link #acceptTransient}) until something reads it as a {@code String}; one
     * array either way, so views cost no extra allocation.
     */
    private Object[] keysAndValues = new Object[2 * INITIAL_PAIRS];

    /**
     * Each pair's {@link HeaderSchema} constant as {@code ordinal() + 1}, or 0 for none: a byte per
     * pair rather than a reference, since there are fewer than 127 constants.
     */
    private byte[] headers = new byte[INITIAL_PAIRS];

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
      add(key, value, key == null ? null : this.keys.header(key));
    }

    /** Keeps {@code value} as a view until something reads it as a {@code String}. */
    @Override
    public void acceptTransient(HeaderSchema header, String key, CharSequence value) {
      // the cache lives for one extraction, so the view stays in its scope
      add(key, value, header);
    }

    private void add(String key, CharSequence value, HeaderSchema header) {
      int index = 2 * this.size;
      if (index == this.keysAndValues.length) {
        this.keysAndValues = Arrays.copyOf(this.keysAndValues, 2 * index);
        this.headers = Arrays.copyOf(this.headers, this.size * 2);
      }
      this.keysAndValues[index] = key;
      this.keysAndValues[index + 1] = value;
      this.headers[this.size] = header == null ? 0 : (byte) (header.ordinal() + 1);
      this.size++;
    }

    @Override
    public void forEachKeyValue(CarrierCache carrier, BiConsumer<String, String> visitor) {
      for (int i = 0; i < carrier.size; i++) {
        visitor.accept(carrier.key(i), carrier.value(i));
      }
    }

    @Override
    public int size() {
      return this.size;
    }

    @Override
    public String key(int index) {
      return (String) this.keysAndValues[2 * index];
    }

    /** The value as a {@code String}, copying a view once (and keeping the copy). */
    @Override
    public String value(int index) {
      Object value = this.keysAndValues[2 * index + 1];
      if (value == null || value instanceof String) {
        return (String) value;
      }
      String copy = value.toString();
      this.keysAndValues[2 * index + 1] = copy;
      return copy;
    }

    @Override
    public CharSequence valueView(int index) {
      return (CharSequence) this.keysAndValues[2 * index + 1];
    }

    @Override
    public HeaderSchema header(int index) {
      int header = this.headers[index];
      return header == 0 ? null : HeaderSchema.fromOrdinal(header - 1);
    }
  }
}
