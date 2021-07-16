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

  public static <T> MapDataBundle ofDelegate(Map<Address<?>, Object> delegate) {
    return new MapDataBundle(delegate);
  }

  public static <T> MapDataBundle of(Address<T> addr, T value) {
    return new MapDataBundle(Collections.singletonMap(addr, value));
  }

  public static <T, U> MapDataBundle of(Address<T> addr1, T value1, Address<U> addr2, U value2) {
    Map<Address<?>, Object> map = new IdentityHashMap<>();
    map.put(addr1, value1);
    map.put(addr2, value2);
    return new MapDataBundle(map);
  }

  public static <T, U, V> MapDataBundle of(
      Address<T> addr1, T value1, Address<U> addr2, U value2, Address<V> addr3, V value3) {
    Map<Address<?>, Object> map = new IdentityHashMap<>();
    map.put(addr1, value1);
    map.put(addr2, value2);
    map.put(addr3, value3);
    return new MapDataBundle(map);
  }

  public static <T, U, V, W> MapDataBundle of(
      Address<T> addr1,
      T value1,
      Address<U> addr2,
      U value2,
      Address<V> addr3,
      V value3,
      Address<W> addr4,
      W value4) {
    Map<Address<?>, Object> map = new IdentityHashMap<>();
    map.put(addr1, value1);
    map.put(addr2, value2);
    map.put(addr3, value3);
    map.put(addr4, value4);
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
}
