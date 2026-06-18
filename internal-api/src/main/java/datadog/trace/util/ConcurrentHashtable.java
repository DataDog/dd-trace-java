package datadog.trace.util;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Concurrent counterpart to {@link Hashtable}. Provides lock-free reads and locked writes for
 * {@link D1} (single-key) and {@link D2} (composite-key) tables.
 *
 * <p>Like {@link Hashtable}, capacity is fixed at construction and the table does not resize.
 * Unlike {@link Hashtable}, all operations are safe for concurrent access without external
 * synchronization.
 *
 * <p>The primary advantage over {@link java.util.concurrent.ConcurrentHashMap} for composite-key
 * use cases is that {@link D2#get(Object, Object)} and {@link D2#getOrCreate(Object, Object,
 * BiFunction)} accept key parts directly — no composite key object is allocated for the lookup.
 * {@code ConcurrentHashMap} requires a wrapper object whose ownership may transfer to the map on
 * insert; escape analysis must conservatively assume the key escapes even on hit paths, preventing
 * scalar replacement.
 *
 * <p><b>Memory model.</b> Bucket slots are held in an {@link AtomicReferenceArray}, so each {@link
 * #get} begins with a volatile read of the slot. Entries are inserted at the bucket head: the
 * new entry's {@code next} pointer is set before the volatile slot write, so any subsequent
 * volatile read of that slot carries happens-before over the full chain — chain {@code next}
 * fields do not need to be volatile.
 */
public final class ConcurrentHashtable {
  private ConcurrentHashtable() {}

  /**
   * Single-key concurrent hash table. Lock-free on hit; locked on miss.
   *
   * @param <K> the key type
   * @param <TEntry> the user's {@link Hashtable.D1.Entry D1.Entry&lt;K&gt;} subclass
   */
  public static final class D1<K, TEntry extends Hashtable.D1.Entry<K>> {

    private final AtomicReferenceArray<Hashtable.Entry> buckets;
    private final AtomicInteger size = new AtomicInteger();

    public D1(int capacity) {
      this.buckets = new AtomicReferenceArray<>(Hashtable.Support.sizeFor(capacity));
    }

    public int size() {
      return size.get();
    }

    @SuppressWarnings("unchecked")
    public TEntry get(K key) {
      long keyHash = Hashtable.D1.Entry.hash(key);
      for (TEntry te = (TEntry) buckets.get(bucketIndex(keyHash)); te != null; te = te.next()) {
        if (te.keyHash == keyHash && te.matches(key)) {
          return te;
        }
      }
      return null;
    }

    /**
     * Returns the entry for {@code key}, creating one via {@code creator} if absent. Lock-free on
     * hit; acquires a table-level lock on miss. Re-checks under the lock to avoid duplicate
     * entries under concurrent misses.
     */
    @SuppressWarnings("unchecked")
    public TEntry getOrCreate(K key, Function<? super K, ? extends TEntry> creator) {
      long keyHash = Hashtable.D1.Entry.hash(key);
      int index = bucketIndex(keyHash);
      for (TEntry te = (TEntry) buckets.get(index); te != null; te = te.next()) {
        if (te.keyHash == keyHash && te.matches(key)) {
          return te;
        }
      }
      synchronized (this) {
        for (TEntry te = (TEntry) buckets.get(index); te != null; te = te.next()) {
          if (te.keyHash == keyHash && te.matches(key)) {
            return te;
          }
        }
        TEntry newEntry = creator.apply(key);
        newEntry.setNext((TEntry) buckets.get(index));
        buckets.set(index, newEntry);
        size.incrementAndGet();
        return newEntry;
      }
    }

    @SuppressWarnings("unchecked")
    public void forEach(Consumer<? super TEntry> consumer) {
      for (int i = 0; i < buckets.length(); i++) {
        for (TEntry te = (TEntry) buckets.get(i); te != null; te = te.next()) {
          consumer.accept(te);
        }
      }
    }

    /**
     * Context-passing forEach. Avoids a capturing-lambda allocation — pass a non-capturing {@link
     * BiConsumer} (typically a {@code static final}) plus whatever side-band state it needs.
     */
    @SuppressWarnings("unchecked")
    public <T> void forEach(T context, BiConsumer<? super T, ? super TEntry> consumer) {
      for (int i = 0; i < buckets.length(); i++) {
        for (TEntry te = (TEntry) buckets.get(i); te != null; te = te.next()) {
          consumer.accept(context, te);
        }
      }
    }

    private int bucketIndex(long keyHash) {
      return (int) (keyHash & (buckets.length() - 1));
    }
  }

  /**
   * Two-key (composite-key) concurrent hash table. Lock-free on hit; locked on miss.
   *
   * <p>Key parts are passed directly to {@link #get} and {@link #getOrCreate}, eliminating the
   * per-lookup composite key object allocation that {@code ConcurrentHashMap<Pair<K1,K2>, V>}
   * requires.
   *
   * @param <K1> first key type
   * @param <K2> second key type
   * @param <TEntry> the user's {@link Hashtable.D2.Entry D2.Entry&lt;K1, K2&gt;} subclass
   */
  public static final class D2<K1, K2, TEntry extends Hashtable.D2.Entry<K1, K2>> {

    private final AtomicReferenceArray<Hashtable.Entry> buckets;
    private final AtomicInteger size = new AtomicInteger();

    public D2(int capacity) {
      this.buckets = new AtomicReferenceArray<>(Hashtable.Support.sizeFor(capacity));
    }

    public int size() {
      return size.get();
    }

    @SuppressWarnings("unchecked")
    public TEntry get(K1 key1, K2 key2) {
      long keyHash = Hashtable.D2.Entry.hash(key1, key2);
      for (TEntry te = (TEntry) buckets.get(bucketIndex(keyHash)); te != null; te = te.next()) {
        if (te.keyHash == keyHash && te.matches(key1, key2)) {
          return te;
        }
      }
      return null;
    }

    /**
     * Returns the entry for {@code (key1, key2)}, creating one via {@code creator} if absent.
     * Lock-free on hit; acquires a table-level lock on miss. Re-checks under the lock to avoid
     * duplicate entries under concurrent misses.
     *
     * <p>The {@code creator} should build an entry whose {@code keyHash} equals {@link
     * Hashtable.D2.Entry#hash(Object, Object) D2.Entry.hash(key1, key2)}.
     */
    @SuppressWarnings("unchecked")
    public TEntry getOrCreate(
        K1 key1, K2 key2, BiFunction<? super K1, ? super K2, ? extends TEntry> creator) {
      long keyHash = Hashtable.D2.Entry.hash(key1, key2);
      int index = bucketIndex(keyHash);
      for (TEntry te = (TEntry) buckets.get(index); te != null; te = te.next()) {
        if (te.keyHash == keyHash && te.matches(key1, key2)) {
          return te;
        }
      }
      synchronized (this) {
        for (TEntry te = (TEntry) buckets.get(index); te != null; te = te.next()) {
          if (te.keyHash == keyHash && te.matches(key1, key2)) {
            return te;
          }
        }
        TEntry newEntry = creator.apply(key1, key2);
        newEntry.setNext((TEntry) buckets.get(index));
        buckets.set(index, newEntry);
        size.incrementAndGet();
        return newEntry;
      }
    }

    @SuppressWarnings("unchecked")
    public void forEach(Consumer<? super TEntry> consumer) {
      for (int i = 0; i < buckets.length(); i++) {
        for (TEntry te = (TEntry) buckets.get(i); te != null; te = te.next()) {
          consumer.accept(te);
        }
      }
    }

    /**
     * Context-passing forEach. Avoids a capturing-lambda allocation — pass a non-capturing {@link
     * BiConsumer} (typically a {@code static final}) plus whatever side-band state it needs.
     */
    @SuppressWarnings("unchecked")
    public <T> void forEach(T context, BiConsumer<? super T, ? super TEntry> consumer) {
      for (int i = 0; i < buckets.length(); i++) {
        for (TEntry te = (TEntry) buckets.get(i); te != null; te = te.next()) {
          consumer.accept(context, te);
        }
      }
    }

    private int bucketIndex(long keyHash) {
      return (int) (keyHash & (buckets.length() - 1));
    }
  }
}
