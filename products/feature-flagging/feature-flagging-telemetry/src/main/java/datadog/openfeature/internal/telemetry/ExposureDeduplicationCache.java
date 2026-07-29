package datadog.openfeature.internal.telemetry;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bounded least-recently-used cache for exposure deduplication.
 *
 * <p>The cache has no dependency on an exposure transport or Java agent model. Callers provide the
 * four UFC identifiers that determine whether an exposure changed.
 *
 * <p>This class is intentionally not thread-safe. The owning telemetry writer must serialize
 * access.
 */
public final class ExposureDeduplicationCache {

  private static final int DEFAULT_INITIAL_CAPACITY = 1 << 4;
  private static final float DEFAULT_LOAD_FACTOR = 0.75f;

  private final Map<Key, Value> cache;
  private final int capacity;

  public ExposureDeduplicationCache(final int capacity) {
    this.capacity = capacity;
    this.cache = new LinkedHashMap<>(DEFAULT_INITIAL_CAPACITY, DEFAULT_LOAD_FACTOR, true);
  }

  /**
   * Records an exposure.
   *
   * @return {@code true} when the flag and subject pair is new or its allocation changed.
   */
  public boolean shouldEmit(
      final String flagKey,
      final String targetingKey,
      final String variantKey,
      final String allocationKey) {
    final Key key = new Key(flagKey, targetingKey);
    final Value value = new Value(variantKey, allocationKey);
    final Value oldValue = cache.put(key, value);
    if (cache.size() > capacity) {
      final Iterator<Key> oldest = cache.keySet().iterator();
      oldest.next();
      oldest.remove();
    }
    return oldValue == null || !oldValue.equals(value);
  }

  Value getValue(final Key key) {
    return cache.get(key);
  }

  int size() {
    return cache.size();
  }

  static final class Key {
    final String flag;
    final String subject;

    Key(final String flag, final String subject) {
      this.flag = flag;
      this.subject = subject;
    }

    @Override
    public boolean equals(final Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Key)) {
        return false;
      }
      final Key key = (Key) other;
      return Objects.equals(flag, key.flag) && Objects.equals(subject, key.subject);
    }

    @Override
    public int hashCode() {
      return Objects.hash(flag, subject);
    }
  }

  static final class Value {
    final String variant;
    final String allocation;

    Value(final String variant, final String allocation) {
      this.variant = variant;
      this.allocation = allocation;
    }

    @Override
    public boolean equals(final Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof Value)) {
        return false;
      }
      final Value value = (Value) other;
      return Objects.equals(variant, value.variant) && Objects.equals(allocation, value.allocation);
    }

    @Override
    public int hashCode() {
      return Objects.hash(variant, allocation);
    }
  }
}
