package com.datadog.appsec.event.data;

import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CaseInsensitiveMap<V> implements Map<String, V> {
  private final Map<String, V> delegate;

  public CaseInsensitiveMap() {
    this.delegate = new HashMap();
  }

  public CaseInsensitiveMap(Map<String, V> mapToCopy) {
    this();
    putAll(mapToCopy);
  }

  private static String lower(final Object key) {
    return key == null ? null : key.toString().toLowerCase(Locale.ENGLISH);
  }

  @Override
  public V get(final Object key) {
    return this.delegate.get(lower(key));
  }

  @Override
  public int size() {
    return this.delegate.size();
  }

  @Override
  public boolean isEmpty() {
    return this.delegate.isEmpty();
  }

  @Override
  public boolean containsKey(final Object key) {
    return this.delegate.containsKey(lower(key));
  }

  @Override
  public boolean containsValue(Object value) {
    return this.delegate.containsValue(value);
  }

  @Override
  public V put(final String key, final V value) {
    return this.delegate.put(lower(key), value);
  }

  @Override
  public void putAll(final Map<? extends String, ? extends V> map) {
    for (Entry<? extends String, ? extends V> entry : map.entrySet()) {
      put(entry.getKey(), entry.getValue());
    }
  }

  @Override
  public void clear() {
    this.delegate.clear();
  }

  @Override
  public Set<String> keySet() {
    return this.delegate.keySet();
  }

  @Override
  public Collection<V> values() {
    return this.delegate.values();
  }

  @Override
  public Set<Entry<String, V>> entrySet() {
    return this.delegate.entrySet();
  }

  @Override
  public V remove(final Object object) {
    return this.delegate.remove(lower(object));
  }
}
