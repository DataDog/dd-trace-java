package com.datadog.appsec.event.data;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

public class MapDataBundle implements DataBundle {
  private final Map<Address<?>, Object> map;

  private MapDataBundle(Map<Address<?>, Object> map) {
    this.map = map;
  }

  public static MapDataBundle ofDelegate(Map<Address<?>, Object> delegate) {
    return new MapDataBundle(delegate);
  }

  public static <T> MapDataBundle of(Address<T> addr, T value) {
    return new MapDataBundle(Collections.singletonMap(addr, value));
  }

  public static <T, U> MapDataBundle of(Address<T> addr1, T value1, Address<U> addr2, U value2) {
    Map<Address<?>, Object> map = new IdentityHashMap<>(4);
    map.put(addr1, value1);
    map.put(addr2, value2);
    return new MapDataBundle(map);
  }

  public static <T, U, V> MapDataBundle of(
      Address<T> addr1, T value1, Address<U> addr2, U value2, Address<V> addr3, V value3) {
    Map<Address<?>, Object> map = new IdentityHashMap<>(8);
    map.put(addr1, value1);
    map.put(addr2, value2);
    map.put(addr3, value3);
    return new MapDataBundle(map);
  }

  @Override
  public boolean hasAddress(Address<?> addr) {
    return map.containsKey(addr);
  }

  @Override
  public Collection<Address<?>> getAllAddresses() {
    return map.keySet();
  }

  @Override
  public int size() {
    return map.size();
  }

  @Override
  public <T> T get(Address<T> addr) {
    return (T) map.get(addr);
  }

  @Override
  public Iterator<Map.Entry<Address<?>, Object>> iterator() {
    return map.entrySet().iterator();
  }

  public static class Builder {

    private final Map<Address<?>, Object> map;

    public static final int CAPACITY_0_2 = 4;
    public static final int CAPACITY_3_4 = 8;
    public static final int CAPACITY_6_10 = 16;
    public static final int CAPACITY_11_21 = 32;
    public static final int CAPACITY_22_42 = 64;

    /**
     * @param capacity 2^ceil(log2(ceil(expected_elements 3/2)))
     */
    public Builder(int capacity) {
      map = new IdentityHashMap<>(capacity);
    }

    public <A extends Address<?>, V> Builder add(A address, V value) {
      if (address == null || value == null) return this;
      if (value instanceof Collection && ((Collection<?>) value).isEmpty()) return this;
      if (value instanceof Map && ((Map<?, ?>) value).isEmpty()) return this;

      map.put(address, value);
      return this;
    }

    public MapDataBundle build() {
      return new MapDataBundle(map);
    }
  }
}
