package datadog.trace.agent.tooling.log;

import java.io.Serializable;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Mutable view over two maps with entries in the primary map taking precedence over the secondary.
 * New entries are put in the primary map while old entries are deleted from both, as appropriate.
 * Lazy deduplication occurs once: before iterating over entries/values, or when combining sizes.
 */
public final class UnionMap<K, V> extends AbstractMap<K, V> implements Serializable {
  private Map<K, V> primaryMap;
  private Map<K, V> secondaryMap;
  private transient Set<Map.Entry<K, V>> entrySet;
  private transient volatile boolean deduped;
  private static final ThreadLocal<UnionMap<?, ?>> TL = new ThreadLocal<>();

  public UnionMap(Map<K, V> primaryMap, Map<K, V> secondaryMap) {
    this.primaryMap = primaryMap;
    this.secondaryMap = secondaryMap;
  }

  @SuppressWarnings({"unchecked"})
  public static <K, V> UnionMap<K, V> create(Map<K, V> primaryMap, Map<K, V> secondaryMap) {
    UnionMap ret = TL.get();
    if (ret == null) {
      ret = new UnionMap(primaryMap, secondaryMap);
      TL.set(ret);
    } else {
      ret.primaryMap = primaryMap;
      ret.secondaryMap = secondaryMap;
      ret.deduped = false;
    }
    return ret;
  }

  private void dedup() {
    if (!deduped) {
      if (primaryMap.isEmpty()) {
        deduped = true;
        return; // nothing to deduplicate
      }
      synchronized (this) {
        if (!deduped) {
          // drop keys from secondary that already exist in primary
          Iterator<K> itr = secondaryMap.keySet().iterator();
          while (itr.hasNext()) {
            if (primaryMap.containsKey(itr.next())) {
              itr.remove();
            }
          }
          deduped = true;
        }
      }
    }
  }

  @Override
  public int size() {
    dedup();
    return primaryMap.size() + secondaryMap.size();
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
    if (primaryMap.containsValue(value)) {
      return true;
    } else {
      dedup();
      return secondaryMap.containsValue(value);
    }
  }

  @Override
  public V get(Object key) {
    V result = primaryMap.get(key);
    return null != result || primaryMap.containsKey(key) ? result : secondaryMap.get(key);
  }

  @Override
  public V put(K key, V value) {
    if (primaryMap.containsKey(key)) {
      return primaryMap.put(key, value);
    } else {
      primaryMap.put(key, value);
      return secondaryMap.remove(key);
    }
  }

  @Override
  public V remove(Object key) {
    if (primaryMap.containsKey(key)) {
      secondaryMap.remove(key);
      return primaryMap.remove(key);
    } else {
      return secondaryMap.remove(key);
    }
  }

  @Override
  public void clear() {
    primaryMap.clear();
    secondaryMap.clear();
    entrySet = primaryMap.entrySet(); // optimization: secondary will now always be empty
    deduped = true;
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
              UnionMap.this.dedup();

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

  public Object writeReplace() {
    return new HashMap<>(this); // serialize de-duplicated copy
  }
}
