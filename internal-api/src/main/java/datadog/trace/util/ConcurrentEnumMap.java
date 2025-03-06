package datadog.trace.util;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;
import javax.annotation.Nonnull;

public class ConcurrentEnumMap<K extends Enum<K>, V> implements Map<K, V> {

  private final K[] enumConstants;
  private final AtomicReferenceArray<V> values;

  public ConcurrentEnumMap(Class<K> enumClass) {
    this.enumConstants = enumClass.getEnumConstants();
    this.values = new AtomicReferenceArray<>(enumConstants.length);
  }

  private int indexOf(Object key) {
    if (key == null) {
      throw new NullPointerException("Key must not be null");
    }
    if (key.getClass() != enumConstants[0].getDeclaringClass()) {
      throw new IllegalArgumentException("Key has wrong enum type");
    }
    @SuppressWarnings("unchecked")
    K castKey = (K) key;
    return castKey.ordinal();
  }

  @Override
  public V get(Object key) {
    int idx = indexOf(key);
    return values.get(idx);
  }

  @Override
  public V put(K key, V value) {
    int idx = indexOf(key);
    return values.getAndSet(idx, value);
  }

  @Override
  public void putAll(@Nonnull Map<? extends K, ? extends V> m) {
    for (Map.Entry<? extends K, ? extends V> e : m.entrySet()) {
      put(e.getKey(), e.getValue());
    }
  }

  @Override
  public V remove(Object key) {
    int idx = indexOf(key);
    return values.getAndSet(idx, null);
  }

  @Override
  public V putIfAbsent(@Nonnull K key, V value) {
    int idx = indexOf(key);
    while (true) {
      V existing = values.get(idx);
      if (existing != null) {
        return existing;
      }
      if (values.compareAndSet(idx, null, value)) {
        return null;
      }
    }
  }

  @Override
  public boolean remove(@Nonnull Object key, Object value) {
    int idx = indexOf(key);
    V cur = values.get(idx);
    if (cur == null || !cur.equals(value)) {
      return false;
    }
    return values.compareAndSet(idx, cur, null);
  }

  @Override
  public boolean replace(@Nonnull K key, @Nonnull V oldValue, @Nonnull V newValue) {
    int idx = indexOf(key);
    return values.compareAndSet(idx, oldValue, newValue);
  }

  @Override
  public V replace(@Nonnull K key, @Nonnull V value) {
    int idx = indexOf(key);
    while (true) {
      V cur = values.get(idx);
      if (cur == null) {
        return null;
      }
      if (values.compareAndSet(idx, cur, value)) {
        return cur;
      }
    }
  }

  @Override
  public int size() {
    int count = 0;
    for (int i = 0; i < values.length(); i++) {
      if (values.get(i) != null) {
        count++;
      }
    }
    return count;
  }

  @Override
  public boolean isEmpty() {
    for (int i = 0; i < values.length(); i++) {
      if (values.get(i) != null) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean containsKey(Object key) {
    return get(key) != null;
  }

  @Override
  public boolean containsValue(Object value) {
    for (int i = 0; i < values.length(); i++) {
      V v = values.get(i);
      if (v != null && v.equals(value)) {
        return true;
      }
    }
    return false;
  }

  @Override
  public void clear() {
    for (int i = 0; i < values.length(); i++) {
      values.set(i, null);
    }
  }

  @Nonnull
  @Override
  public Set<K> keySet() {
    Set<K> keys = EnumSet.noneOf(enumConstants[0].getDeclaringClass());
    for (int i = 0; i < values.length(); i++) {
      if (values.get(i) != null) {
        keys.add(enumConstants[i]);
      }
    }
    return Collections.unmodifiableSet(keys);
  }

  @Nonnull
  @Override
  public Collection<V> values() {
    List<V> list = new ArrayList<>();
    for (int i = 0; i < values.length(); i++) {
      V value = values.get(i);
      if (value != null) {
        list.add(value);
      }
    }
    return Collections.unmodifiableList(list);
  }

  @Nonnull
  @Override
  public Set<Map.Entry<K, V>> entrySet() {
    Set<Map.Entry<K, V>> entrySet = new java.util.HashSet<>();
    for (int i = 0; i < enumConstants.length; i++) {
      V value = values.get(i);
      if (value != null) {
        K key = enumConstants[i];
        entrySet.add(new AbstractMap.SimpleEntry<>(key, value));
      }
    }
    return Collections.unmodifiableSet(entrySet);
  }
}
