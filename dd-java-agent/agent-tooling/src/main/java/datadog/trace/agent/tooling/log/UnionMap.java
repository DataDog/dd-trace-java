package datadog.trace.agent.tooling.log;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Mutable view over two maps with entries in the primary taking precedence over the secondary;
 * additions are put in the primary map.
 */
public final class UnionMap<K, V> extends AbstractMap<K, V> {
  private final Map<K, V> primaryMap;
  private final Map<K, V> secondaryMap;
  private transient Set<Map.Entry<K, V>> entrySet;

  public UnionMap(Map<K, V> primaryMap, Map<K, V> secondaryMap) {
    this.primaryMap = primaryMap;
    this.secondaryMap = secondaryMap;
  }

  @Override
  public int size() {
    int primarySize = primaryMap.size();
    int secondarySize = secondaryMap.size();
    if (primarySize > secondarySize) {
      for (K key : secondaryMap.keySet()) {
        if (primaryMap.containsKey(key)) {
          secondarySize--; // account for duplicate key
        }
      }
    } else {
      for (K key : primaryMap.keySet()) {
        if (secondaryMap.containsKey(key)) {
          primarySize--; // account for duplicate key
        }
      }
    }
    return primarySize + secondarySize;
  }

  @Override
  public boolean isEmpty() {
    return primaryMap.isEmpty() && secondaryMap.isEmpty();
  }

  @Override
  public boolean containsKey(Object key) {
    return primaryMap.containsKey(key) || secondaryMap.containsKey(key);
  }

  @Override
  public boolean containsValue(Object value) {
    return primaryMap.containsValue(value) || secondaryMap.containsValue(value);
  }

  @Override
  public V get(Object key) {
    V result = primaryMap.get(key);
    return null != result || primaryMap.containsKey(key) ? result : secondaryMap.get(key);
  }

  @Override
  public V put(K key, V value) {
    V result = primaryMap.put(key, value);
    return null != result || !secondaryMap.containsKey(key) ? result : secondaryMap.remove(key);
  }

  @Override
  public V remove(Object key) {
    V result = primaryMap.remove(key);
    return null != result || !secondaryMap.containsKey(key) ? result : secondaryMap.remove(key);
  }

  @Override
  public void clear() {
    primaryMap.clear();
    secondaryMap.clear();
    entrySet = primaryMap.entrySet(); // optimization: secondary will now always be empty
  }

  @Override
  public Set<Entry<K, V>> entrySet() {
    if (null == entrySet) {
      entrySet =
          new AbstractSet<Map.Entry<K, V>>() {
            @Override
            public int size() {
              return UnionMap.this.size();
            }

            @Override
            public Iterator<Map.Entry<K, V>> iterator() {
              return new Iterator<Map.Entry<K, V>>() {
                private Iterator<Map.Entry<K, V>> itr = primaryMap.entrySet().iterator();
                private volatile boolean trySecondaryNext = !secondaryMap.isEmpty();

                @Override
                public boolean hasNext() {
                  return itr.hasNext() || trySecondaryNext;
                }

                @Override
                public Map.Entry<K, V> next() {
                  if (!itr.hasNext() && trySecondaryNext) {
                    trySecondaryNext = false;
                    itr = secondaryMap.entrySet().iterator();
                  }
                  return itr.next();
                }

                @Override
                public void remove() {
                  itr.remove();
                }
              };
            }
          };
    }
    return entrySet;
  }
}
