package datadog.trace.instrumentation.springweb6;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * A read-only Map view that lazily merges two maps. When both maps contain the same key, the values
 * are combined into a {@link PairList}. The actual merge is deferred until the first operation that
 * requires it (e.g. entrySet, size), avoiding unnecessary HashMap copies when the map is never
 * iterated.
 */
public final class MergedMapView implements Map<String, Object> {

  private final Map<String, Object> primary;
  private final Map<String, Object> secondary;
  private Map<String, Object> merged;

  public MergedMapView(Map<String, Object> primary, Map<String, Object> secondary) {
    this.primary = primary;
    this.secondary = secondary;
  }

  private Map<String, Object> merged() {
    if (merged == null) {
      merged = new HashMap<>(primary);
      for (Map.Entry<String, Object> e : secondary.entrySet()) {
        String key = e.getKey();
        Object curValue = merged.get(key);
        if (curValue != null) {
          merged.put(key, new PairList(curValue, e.getValue()));
        } else {
          merged.put(key, e.getValue());
        }
      }
    }
    return merged;
  }

  @Override
  public Object get(Object key) {
    Object v1 = primary.get(key);
    Object v2 = secondary.get(key);
    if (v1 != null && v2 != null) {
      return new PairList(v1, v2);
    }
    return v1 != null ? v1 : v2;
  }

  @Override
  public boolean containsKey(Object key) {
    return primary.containsKey(key) || secondary.containsKey(key);
  }

  @Override
  public int size() {
    return merged().size();
  }

  @Override
  public boolean isEmpty() {
    return primary.isEmpty() && secondary.isEmpty();
  }

  @Override
  public boolean containsValue(Object value) {
    return merged().containsValue(value);
  }

  @Override
  public Object put(String key, Object value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Object remove(Object key) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void putAll(Map<? extends String, ?> m) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void clear() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Set<String> keySet() {
    return merged().keySet();
  }

  @Override
  public Collection<Object> values() {
    return merged().values();
  }

  @Override
  public Set<Entry<String, Object>> entrySet() {
    return merged().entrySet();
  }

  @Override
  public boolean equals(Object o) {
    return merged().equals(o);
  }

  @Override
  public int hashCode() {
    return merged().hashCode();
  }

  @Override
  public String toString() {
    return merged().toString();
  }
}
