package datadog.trace.instrumentation.spark;

import java.util.LinkedHashMap;
import java.util.Map;

public class RemoveEldestHashMap<K, V> extends LinkedHashMap<K, V> {
  private final int maxSize;

  public RemoveEldestHashMap(int maxSize) {
    this.maxSize = maxSize;
  }

  @Override
  protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
    return size() > maxSize;
  }
}
