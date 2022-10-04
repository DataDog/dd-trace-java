package com.datadog.iast.taint;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Default implementation for {@link TaintedMap}. It is optimized for low concurrency scenarios, but
 * never fail with high concurrency.
 *
 * <p><i>Capacity</i> is fixed, so there is no rehashing. Once it reaches a <i>flat mode
 * threshold</i>, the table switches to flat mode. In this mode, every new put will be inserted at
 * the head of the bucket, and any tail (colliding entries) will be discarded. Once a map switches
 * to flat mode, it never goes back from it. Note that entries for garbage collected entries are
 * removed before this threshold is checked.
 *
 * <p>This implementation works reasonably well under high concurrency, but it will lose some writes
 * in that case.
 */
public final class DefaultTaintedMap implements TaintedMap {

  /** Default capacity. It MUST be a power of 2. */
  public static final int DEFAULT_CAPACITY = 1 << 14;
  /** Default flat mode threshold. */
  public static final int DEFAULT_FLAT_MODE_THRESHOLD = 1 << 13;
  /** Periodicity of table purges, as number of put operations. It MUST be a power of two. */
  private static final int PURGE_COUNT = 1 << 6;
  /** Bitmask for fast modulo with PURGE_COUNT. */
  private static final int PURGE_MASK = PURGE_COUNT - 1;
  /** Bitmask to convert hashes to positive integers. */
  static final int POSITIVE_MASK = Integer.MAX_VALUE;

  private final TaintedObject[] table;
  /** Bitmask for fast modulo with table length. */
  private final int lengthMask;
  /** Flag to ensure we do not run multiple purges concurrently. */
  private final AtomicBoolean isPurging = new AtomicBoolean(false);
  /**
   * Estimated number of hash table entries. If the hash table switches to flat mode, it stops
   * counting elements.
   */
  private final AtomicInteger estimatedSize = new AtomicInteger(0);
  /** Reference queue for garbage-collected entries. */
  private ReferenceQueue<Object> referenceQueue = new ReferenceQueue<>();
  /**
   * Whether flat mode is enabled or not. Once this is true, it is not set to false again unless
   * {@link #clear()} is called.
   */
  private boolean isFlat = false;
  /** Number of elements in the hash table before switching to flat mode. */
  private final int flatModeThreshold;

  /**
   * Default constructor. Uses {@link #DEFAULT_CAPACITY} and {@link #DEFAULT_FLAT_MODE_THRESHOLD}.
   */
  public DefaultTaintedMap() {
    this(DEFAULT_CAPACITY, DEFAULT_FLAT_MODE_THRESHOLD);
  }

  /**
   * Create a new hash map with the given capacity and flat mode threshold.
   *
   * @param capacity Capacity of the internal array. It must be a power of 2.
   * @param flatModeThreshold Limit of entries before switching to flat mode.
   */
  @SuppressWarnings("unchecked")
  private DefaultTaintedMap(final int capacity, final int flatModeThreshold) {
    table = new TaintedObject[capacity];
    lengthMask = table.length - 1;
    this.flatModeThreshold = flatModeThreshold;
  }

  /**
   * Returns the {@link TaintedObject} for the given input object.
   *
   * @param key Key object.
   * @return The {@link TaintedObject} if it exists, {@code null} otherwise.
   */
  @Override
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
   * Put a new {@link TaintedObject} in the hash table. This method will lose puts in concurrent
   * scenarios.
   *
   * @param entry Tainted object.
   */
  @Override
  public void put(final @Nonnull TaintedObject entry) {
    final int index = index(entry.positiveHashCode);
    if (isFlat) {
      // If we flipped to flat mode:
      // - Always override elements ignoring chaining.
      // - Stop updating the estimated size.

      // TODO: Fix the pathological case where all puts have the same identityHashCode (e.g. limit
      // chain length?)
      table[index] = entry;
      if ((entry.positiveHashCode & PURGE_MASK) == 0) {
        purge();
      }
    } else {
      // By default, add the new entry to the head of the chain.
      // We do not control duplicate keys (although we expect they are generally not used).
      entry.next = table[index];
      table[index] = entry;
      if ((entry.positiveHashCode & PURGE_MASK) == 0) {
        // To mitigate the cost of maintaining an atomic counter, we only update the size every
        // <PURGE_COUNT> puts. This is just an approximation, we rely on key's identity hash code as
        // if it was a random number generator, and we assume duplicate keys are rarely inserted.
        estimatedSize.addAndGet(PURGE_COUNT);
        purge();
      }
    }
  }

  /**
   * Purge entries that have been garbage collected. Only one concurrent call to this method is
   * allowed, further concurrent calls will be ignored.
   */
  void purge() {
    // Ensure we enter only once concurrently.
    if (!isPurging.compareAndSet(false, true)) {
      return;
    }

    try {
      // Remove GC'd entries.
      Reference<?> ref;
      int removedCount = 0;
      while ((ref = referenceQueue.poll()) != null) {
        // This would be an extremely rare bug, and maybe it should produce a health metric or
        // warning.
        if (!(ref instanceof TaintedObject)) {
          continue;
        }
        final TaintedObject entry = (TaintedObject) ref;
        removedCount += remove(entry);
      }

      if (estimatedSize.addAndGet(-removedCount) > flatModeThreshold) {
        isFlat = true;
      }
    } finally {
      // Reset purging flag.
      isPurging.set(false);
    }
  }

  /**
   * Removes a {@link TaintedObject} from the hash table. This method will lose puts in concurrent
   * scenarios.
   *
   * @param entry
   * @return Number of removed elements.
   */
  private int remove(final TaintedObject entry) {
    // A remove might be lost when it is concurrent to puts. If that happens, the object will not be
    // removed again, (until the map goes to flat mode). When a remove is lost under concurrency,
    // this method will still return 1, and it will still be subtracted from the map size estimate.
    // If this is infrequent enough, this would lead to a performance degradation of get opertions.
    // If this happens extremely frequently, like number of lost removals close to number of puts,
    // it could prevent the map from ever going into flat mode, and its size might become
    // effectively unbound.
    final int index = index(entry.positiveHashCode);
    TaintedObject cur = table[index];
    if (cur == entry) {
      table[index] = cur.next;
      return 1;
    }
    if (cur == null) {
      return 0;
    }
    for (TaintedObject prev = cur.next; cur != null; prev = cur, cur = cur.next) {
      if (cur == entry) {
        prev.next = cur.next;
        return 1;
      }
    }
    // If we reach this point, the entry was already removed or put was lost.
    return 0;
  }

  @Override
  public void clear() {
    isFlat = false;
    estimatedSize.set(0);
    Arrays.fill(table, null);
    referenceQueue = new ReferenceQueue<>();
  }

  @Override
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
      TaintedObject currentSubPos;

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

  @Override
  public Iterator<TaintedObject> iterator() {
    return iterator(0, table.length);
  }

  /** Testing only. */
  boolean isFlat() {
    return isFlat;
  }
}
