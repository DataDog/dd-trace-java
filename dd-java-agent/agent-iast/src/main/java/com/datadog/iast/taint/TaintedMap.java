package com.datadog.iast.taint;

import com.datadog.iast.util.NonBlockingSemaphore;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Map optimized for taint tracking.
 *
 * <p>This implementation is optimized for low concurrency scenarios, but never fails with high
 * concurrency.
 *
 * <p>>This is intentionally a smaller API compared to {@link java.util.Map}. It is a hardcoded
 * interface for {@link TaintedObject} entries, which can be used themselves directly as hash table
 * entries and weak references.
 *
 * <p>This implementation is subject to the following characteristics:
 *
 * <ol>
 *   <li>Keys MUST be compared with identity.
 *   <li>Entries SHOULD be removed when key objects are garbage-collected.
 *   <li>All operations MUST NOT throw, even with concurrent access and modification.
 *   <li>Put operations MAY be lost under concurrent modification.
 * </ol>
 *
 * <p><i>Capacity</i> is fixed, so there is no rehashing.
 *
 * <p>This implementation works reasonably well under high concurrency, but it will lose some writes
 * in that case.
 */
public final class TaintedMap implements Iterable<TaintedObject> {

  /** Default capacity. It MUST be a power of 2. */
  public static final int DEFAULT_CAPACITY = 1 << 20;

  /** Periodicity of table purges, as number of put operations. It MUST be a power of two. */
  static final int PURGE_COUNT = 1 << 6;

  /** Bitmask for fast modulo with PURGE_COUNT. */
  static final int PURGE_MASK = PURGE_COUNT - 1;

  /** Bitmask to convert hashes to positive integers. */
  static final int POSITIVE_MASK = Integer.MAX_VALUE;

  private final TaintedObject[] table;

  /** Bitmask for fast modulo with table length. */
  private final int lengthMask;

  /** Flag to ensure we do not run multiple purges concurrently. */
  private final NonBlockingSemaphore purge = NonBlockingSemaphore.withPermitCount(1);

  /** Reference queue for garbage-collected entries. */
  private ReferenceQueue<Object> referenceQueue;

  /** Default constructor. Uses {@link #DEFAULT_CAPACITY} */
  public TaintedMap() {
    this(DEFAULT_CAPACITY);
  }

  /**
   * Create a new hash map with the given capacity.
   *
   * @param capacity Capacity of the internal array. It must be a power of 2.
   */
  public TaintedMap(final int capacity) {
    this(capacity, new ReferenceQueue<>());
  }

  /**
   * Create a new hash map with the given capacity a purge queue.
   *
   * @param capacity Capacity of the internal array. It must be a power of 2.
   * @param queue Reference queue. Only for tests.
   */
  TaintedMap(final int capacity, final ReferenceQueue<Object> queue) {
    table = new TaintedObject[capacity];
    lengthMask = table.length - 1;
    this.referenceQueue = queue;
  }

  /**
   * Returns the {@link TaintedObject} for the given input object.
   *
   * @param key Key object.
   * @return The {@link TaintedObject} if it exists, {@code null} otherwise.
   */
  @Nullable
  public TaintedObject get(final @Nonnull Object key) {
    final int index = indexObject(key);
    TaintedObject entry = table[index];
    while (entry != null) {
      if (key == entry.get()) {
        return entry;
      }
      entry = entry.next;
    }
    return null;
  }

  /**
   * Put a new {@link TaintedObject} in the hash table, always to the tail of the chain. It will not
   * insert the element if it is already present in the map.. This method will lose puts in
   * concurrent scenarios.
   *
   * @param entry Tainted object.
   */
  public void put(final @Nonnull TaintedObject entry) {
    final int index = index(entry.positiveHashCode);
    TaintedObject cur = head(index);
    if (cur == null) {
      table[index] = entry;
    } else {
      TaintedObject next;
      while ((next = next(cur)) != null) {
        if (cur.positiveHashCode == entry.positiveHashCode && cur.get() == entry.get()) {
          // Duplicate, exit early.
          return;
        }
        cur = next;
      }
      cur.next = entry;
    }
    if ((entry.positiveHashCode & PURGE_MASK) == 0) {
      purge();
    }
  }

  /** Gets the head of the bucket removing intermediate GC'ed objects */
  @Nullable
  private TaintedObject head(final int index) {
    final TaintedObject head = firstAliveReference(table[index]);
    table[index] = head;
    return head;
  }

  /** Gets the next tainted object removing intermediate GC'ed objects */
  @Nullable
  private TaintedObject next(@Nonnull final TaintedObject item) {
    final TaintedObject next = firstAliveReference(item.next);
    item.next = next;
    return next;
  }

  /** Gets the first reachable reference that has not been GC'ed */
  @Nullable
  private TaintedObject firstAliveReference(@Nullable TaintedObject item) {
    while (item != null && item.get() == null) {
      item = item.next;
    }
    return item;
  }

  /**
   * Purge entries that have been garbage collected. Only one concurrent call to this method is
   * allowed, further concurrent calls will be ignored.
   */
  void purge() {
    // Ensure we enter only once concurrently.
    if (!purge.acquire()) {
      return;
    }

    try {
      // Remove GC'd entries.
      Reference<?> ref;
      while ((ref = referenceQueue.poll()) != null) {
        // This would be an extremely rare bug, and maybe it should produce a health metric or
        // warning.
        if (!(ref instanceof TaintedObject)) {
          continue;
        }
        final TaintedObject entry = (TaintedObject) ref;
        remove(entry);
      }
    } finally {
      // Reset purging flag.
      purge.release();
    }
  }

  /**
   * Removes a {@link TaintedObject} from the hash table. This method will lose puts in concurrent
   * scenarios.
   *
   * @param entry Tainted object.
   * @return Number of removed elements.
   */
  private int remove(final TaintedObject entry) {
    // A remove might be lost when it is concurrent to puts. When a remove is lost under
    // concurrency,
    // this method will still return 1. Lost removals can be still catch during the iteration over
    // buckets in a put operation.
    final int index = index(entry.positiveHashCode);
    TaintedObject cur = table[index];
    if (cur == entry) {
      table[index] = cur.next;
      return 1;
    }
    if (cur == null) {
      return 0;
    }
    for (TaintedObject prev = cur.next; cur != null && prev != null; prev = cur, cur = cur.next) {
      if (cur == entry) {
        prev.next = cur.next;
        return 1;
      }
    }
    // If we reach this point, the entry was already removed or put was lost.
    return 0;
  }

  public void clear() {
    Arrays.fill(table, null);
    referenceQueue = new ReferenceQueue<>();
  }

  public ReferenceQueue<Object> getReferenceQueue() {
    return referenceQueue;
  }

  private int indexObject(final Object obj) {
    return index(positiveHashCode(System.identityHashCode(obj)));
  }

  private int positiveHashCode(final int h) {
    return h & POSITIVE_MASK;
  }

  private int index(int h) {
    return h & lengthMask;
  }

  private Iterator<TaintedObject> iterator(final int start, final int stop) {
    return new Iterator<TaintedObject>() {
      int currentIndex = start;
      @Nullable TaintedObject currentSubPos;

      @Override
      public boolean hasNext() {
        if (currentSubPos != null) {
          return true;
        }
        for (; currentIndex < stop; currentIndex++) {
          if (table[currentIndex] != null) {
            return true;
          }
        }
        return false;
      }

      @Override
      public TaintedObject next() {
        if (currentSubPos != null) {
          TaintedObject toReturn = currentSubPos;
          currentSubPos = toReturn.next;
          return toReturn;
        }
        for (; currentIndex < stop; currentIndex++) {
          final TaintedObject entry = table[currentIndex];
          if (entry != null) {
            currentSubPos = entry.next;
            currentIndex++;
            return entry;
          }
        }
        throw new NoSuchElementException();
      }
    };
  }

  @Nonnull
  @Override
  public Iterator<TaintedObject> iterator() {
    return iterator(0, table.length);
  }

  public int count() {
    int size = 0;
    for (int i = 0; i < table.length; i++) {
      TaintedObject entry = table[i];
      while (entry != null) {
        entry = entry.next;
        size++;
      }
    }
    return size;
  }
}
