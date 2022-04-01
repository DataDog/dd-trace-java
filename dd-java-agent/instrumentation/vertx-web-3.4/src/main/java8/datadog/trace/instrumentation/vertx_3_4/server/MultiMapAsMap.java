package datadog.trace.instrumentation.vertx_3_4.server;

import io.vertx.core.MultiMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class MultiMapAsMap implements Map<String, Collection<String>> {
  private final MultiMap multimap;
  private volatile Map<String, Collection<String>> map;

  public MultiMapAsMap(MultiMap multimap) {
    this.multimap = multimap;
  }

  private Map<String, Collection<String>> getMap() {
    if (map != null) {
      return map;
    }

    Map<String, Collection<String>> localMap = new HashMap<>();
    for (Entry<String, String> e : multimap) {
      Collection<String> storedValues = localMap.get(e.getKey());
      if (storedValues == null) {
        storedValues = new ArrayList<>(1);
        localMap.put(e.getKey(), storedValues);
      }
      storedValues.add(e.getValue());
    }

    return (map = localMap);
  }

  @Override
  public int size() {
    return multimap.size();
  }

  @Override
  public boolean isEmpty() {
    return multimap.isEmpty();
  }

  @Override
  public boolean containsKey(Object key) {
    if (!(key instanceof String)) {
      return false;
    }
    return multimap.contains((String) key);
  }

  @Override
  public boolean containsValue(Object value) {
    return getMap().containsValue(value);
  }

  @Override
  public Collection<String> get(Object key) {
    return getMap().get(key);
  }

  @Override
  public Collection<String> put(String key, Collection<String> value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Collection<String> remove(Object key) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void putAll(Map<? extends String, ? extends Collection<String>> m) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clear() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<String> keySet() {
    return getMap().keySet();
  }

  @Override
  public Collection<Collection<String>> values() {
    return getMap().values();
  }

  @Override
  public Set<Entry<String, Collection<String>>> entrySet() {
    return getMap().entrySet();
  }
}
