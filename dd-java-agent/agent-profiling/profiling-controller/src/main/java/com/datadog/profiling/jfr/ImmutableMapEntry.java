package com.datadog.profiling.jfr;

import java.util.Map;
import java.util.Objects;

import lombok.Generated;
import lombok.Getter;
import lombok.NonNull;

@Generated
final class ImmutableMapEntry<K, V> implements Map.Entry<K, V> {
  private volatile boolean computeHashCode = true;
  private int hashCode;

  @Getter private final K key;
  @Getter private final V value;

  ImmutableMapEntry(@NonNull K key, V value) {
    this.key = key;
    this.value = value;
  }

  @Override
  public V setValue(V v) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    ImmutableMapEntry<?, ?> that = (ImmutableMapEntry<?, ?>) o;
    return key.equals(that.key) && Objects.equals(value, that.value);
  }

  @Override
  public int hashCode() {
    if (computeHashCode) {
      hashCode = Objects.hash(key, value);
      computeHashCode = false;
    }
    return hashCode;
  }
}
