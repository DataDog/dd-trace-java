package datadog.trace.api;

import java.lang.ref.WeakReference;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

public final class SimplePooledMap extends AbstractMap<String, Object> {

  /** Weak pooled values per poolKey */
  private static final Map<String, ArrayBlockingQueue<WeakReference<SimplePooledMap>>> POOLS =
      new ConcurrentHashMap<>();

  /** Max number of retained entries per poolKey */
  private static final int MAX_RETAINED = 128;

  /** Acquire an instance, reusing from pool if possible */
  public static SimplePooledMap acquire(String poolKey, int initialCapacity) {
    ArrayBlockingQueue<WeakReference<SimplePooledMap>> queue =
        POOLS.computeIfAbsent(poolKey, k -> new ArrayBlockingQueue<>(MAX_RETAINED));

    while (true) {
      WeakReference<SimplePooledMap> ref = queue.poll();
      if (ref == null) {
        break; // nothing reusable
      }
      SimplePooledMap map = ref.get();
      if (map != null) {
        map.clear();
        return map;
      }
      // dead ref → continue polling
    }

    // Nothing reusable: create new instance
    return new SimplePooledMap(initialCapacity);
  }

  /** Return instance to pool. */
  public static void release(String poolKey, SimplePooledMap map) {
    ArrayBlockingQueue<WeakReference<SimplePooledMap>> queue =
        POOLS.computeIfAbsent(poolKey, k -> new ArrayBlockingQueue<>(MAX_RETAINED));

    // Offer the map back if there's space; otherwise, drop it
    queue.offer(new WeakReference<>(map));
  }

  private String[] keys;
  private Object[] values;
  private boolean[] visible;

  /** Occupied slot indexes — avoids scanning whole table */
  private int[] occupied;

  private int occCount;

  private int capacity;
  private int mask;
  private int size;
  private final float loadFactor;

  public SimplePooledMap(int initialCapacity) {
    this(initialCapacity, 0.60f);
  }

  public SimplePooledMap(int initialCapacity, float loadFactor) {
    this.loadFactor = loadFactor;
    this.capacity = tableSizeFor(initialCapacity);
    this.mask = capacity - 1;
    this.keys = new String[capacity];
    this.values = new Object[capacity];
    this.visible = new boolean[capacity];
    this.occupied = new int[capacity];
    this.occCount = 0;
    this.size = 0;
  }

  /** Next power-of-two */
  private static int tableSizeFor(int n) {
    if (n <= 1) {
      return 1;
    }
    return 1 << (32 - Integer.numberOfLeadingZeros(n - 1));
  }

  private static int spread(int h) {
    return (h ^ (h >>> 16));
  }

  /** Locate slot using linear probing */
  private int findSlot(String key, String[] table) {
    int idx = spread(key.hashCode()) & (table.length - 1);
    while (true) {
      String k = table[idx];
      if (k == null || k.equals(key)) {
        return idx;
      }
      idx = (idx + 1) & (table.length - 1);
    }
  }

  private void ensureCapacity() {
    if ((size + 1) >= (int) (capacity * loadFactor)) {
      grow();
    }
  }

  private void grow() {
    int newCap = capacity << 1;

    String[] oldKeys = keys;
    Object[] oldValues = values;
    boolean[] oldVisible = visible;
    int[] oldOccupied = occupied;
    int oldOccCount = occCount;

    keys = new String[newCap];
    values = new Object[newCap];
    visible = new boolean[newCap];
    occupied = new int[newCap];

    capacity = newCap;
    mask = newCap - 1;
    occCount = 0;
    size = 0;

    // Only reinsert visible entries
    for (int i = 0; i < oldOccCount; i++) {
      int oldIdx = oldOccupied[i];
      if (oldVisible[oldIdx]) {
        String k = oldKeys[oldIdx];
        Object v = oldValues[oldIdx];

        int idx = findSlot(k, keys);
        keys[idx] = k;
        values[idx] = v;
        visible[idx] = true;

        occupied[occCount++] = idx;
        size++;
      }
    }
  }

  @Override
  public Object put(String key, Object value) {
    ensureCapacity();

    int idx = spread(key.hashCode()) & mask;

    // Fast-path linear probing
    while (true) {
      String k = keys[idx];

      if (k == null) {
        // First insertion
        keys[idx] = key;
        values[idx] = value;
        visible[idx] = true;

        occupied[occCount++] = idx;
        size++;
        return null;
      }

      if (k.equals(key)) {
        if (!visible[idx]) {
          // Key exists but hidden
          values[idx] = value;
          visible[idx] = true;
          size++;
          return null;
        } else {
          // Key exists and visible
          Object old = values[idx];
          values[idx] = value;
          return old;
        }
      }

      idx = (idx + 1) & mask;
    }
  }

  @Override
  public Object get(Object keyObj) {
    if (!(keyObj instanceof String)) {
      return null;
    }

    String key = (String) keyObj;
    int idx = spread(key.hashCode()) & mask;

    while (true) {
      String k = keys[idx];
      if (k == null) {
        return null;
      }
      if (k.equals(key)) {
        if (visible[idx]) {
          return values[idx];
        } else {
          return null;
        }
      }
      idx = (idx + 1) & mask;
    }
  }

  @Override
  public boolean containsKey(Object keyObj) {
    if (!(keyObj instanceof String)) {
      return false;
    }

    String key = (String) keyObj;
    int idx = spread(key.hashCode()) & mask;

    while (true) {
      String k = keys[idx];
      if (k == null) {
        return false;
      }
      if (k.equals(key)) {
        return visible[idx];
      }
      idx = (idx + 1) & mask;
    }
  }

  // ⚡ remove without clearing values[]
  @Override
  public Object remove(Object keyObj) {
    if (!(keyObj instanceof String)) {
      return null;
    }

    String key = (String) keyObj;
    int idx = spread(key.hashCode()) & mask;

    while (true) {
      String k = keys[idx];
      if (k == null) {
        return null;
      }
      if (k.equals(key)) {
        if (visible[idx]) {
          visible[idx] = false;
          size--;
          return values[idx];
        } else {
          return null;
        }
      }
      idx = (idx + 1) & mask;
    }
  }

  @Override
  public int size() {
    return size;
  }

  /** Clear only visible entries — O(size), not O(capacity) */
  @Override
  public void clear() {
    for (int i = 0; i < occCount; i++) {
      int idx = occupied[i];
      if (visible[idx]) {
        visible[idx] = false;
      }
    }
    size = 0;
  }

  @Override
  public Set<Entry<String, Object>> entrySet() {
    return new EntrySetView();
  }

  private final class EntrySetView extends AbstractSet<Entry<String, Object>> {

    @Override
    public int size() {
      return SimplePooledMap.this.size;
    }

    @Override
    public Iterator<Entry<String, Object>> iterator() {
      return new EntryIterator();
    }
  }

  /** GC-free reusable entry */
  private static final class ReusableEntry implements Entry<String, Object> {
    private String key;
    private Object value;

    private void reset(String k, Object v) {
      this.key = k;
      this.value = v;
    }

    @Override
    public String getKey() {
      return key;
    }

    @Override
    public Object getValue() {
      return value;
    }

    @Override
    public Object setValue(Object value) {
      throw new UnsupportedOperationException("Mutable entry not supported");
    }
  }

  private final class EntryIterator implements Iterator<Entry<String, Object>> {
    private int pos = 0;
    private final ReusableEntry entry = new ReusableEntry();

    @Override
    public boolean hasNext() {
      while (pos < occCount) {
        int idx = occupied[pos];
        if (visible[idx]) {
          return true;
        } else {
          pos++;
        }
      }
      return false;
    }

    @Override
    public Entry<String, Object> next() {
      while (pos < occCount && !visible[occupied[pos]]) {
        pos++;
      }
      if (pos >= occCount) {
        throw new NoSuchElementException();
      }

      int idx = occupied[pos++];
      entry.reset(keys[idx], values[idx]);
      return entry;
    }
  }
}
