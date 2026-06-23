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
 * #get} begins with a volatile read of the slot. Entries are inserted at the bucket head: the new
 * entry's {@code next} pointer is set before the volatile slot write, so any subsequent volatile
 * read of that slot carries happens-before over the full chain — chain {@code next} fields do not
 * need to be volatile.
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

    public TEntry get(K key) {
      long keyHash = Hashtable.D1.Entry.hash(key);
      for (TEntry te = Support.bucket(buckets, keyHash); te != null; te = te.next()) {
        if (te.keyHash == keyHash && te.matches(key)) {
          return te;
        }
      }
      return null;
    }

    /**
     * Returns the entry for {@code key}, creating one via {@code creator} if absent. Lock-free on
     * hit; acquires a table-level lock on miss. Re-checks under the lock to avoid duplicate entries
     * under concurrent misses.
     */
    public TEntry getOrCreate(K key, Function<? super K, ? extends TEntry> creator) {
      long keyHash = Hashtable.D1.Entry.hash(key);
      int index = Support.bucketIndex(buckets, keyHash);
      for (TEntry te = Support.bucket(buckets, index); te != null; te = te.next()) {
        if (te.keyHash == keyHash && te.matches(key)) {
          return te;
        }
      }
      synchronized (this) {
        for (TEntry te = Support.bucket(buckets, index); te != null; te = te.next()) {
          if (te.keyHash == keyHash && te.matches(key)) {
            return te;
          }
        }
        TEntry newEntry = creator.apply(key);
        newEntry.setNext(Support.bucket(buckets, index));
        buckets.set(index, newEntry);
        size.incrementAndGet();
        return newEntry;
      }
    }

    public void forEach(Consumer<? super TEntry> consumer) {
      Support.forEach(buckets, consumer);
    }

    /**
     * Context-passing forEach. Avoids a capturing-lambda allocation — pass a non-capturing {@link
     * BiConsumer} (typically a {@code static final}) plus whatever side-band state it needs.
     */
    public <T> void forEach(T context, BiConsumer<? super T, ? super TEntry> consumer) {
      Support.forEach(buckets, context, consumer);
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

    public TEntry get(K1 key1, K2 key2) {
      long keyHash = Hashtable.D2.Entry.hash(key1, key2);
      for (TEntry te = Support.bucket(buckets, keyHash); te != null; te = te.next()) {
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
    public TEntry getOrCreate(
        K1 key1, K2 key2, BiFunction<? super K1, ? super K2, ? extends TEntry> creator) {
      long keyHash = Hashtable.D2.Entry.hash(key1, key2);
      int index = Support.bucketIndex(buckets, keyHash);
      for (TEntry te = Support.bucket(buckets, index); te != null; te = te.next()) {
        if (te.keyHash == keyHash && te.matches(key1, key2)) {
          return te;
        }
      }
      synchronized (this) {
        for (TEntry te = Support.bucket(buckets, index); te != null; te = te.next()) {
          if (te.keyHash == keyHash && te.matches(key1, key2)) {
            return te;
          }
        }
        TEntry newEntry = creator.apply(key1, key2);
        newEntry.setNext(Support.bucket(buckets, index));
        buckets.set(index, newEntry);
        size.incrementAndGet();
        return newEntry;
      }
    }

    public void forEach(Consumer<? super TEntry> consumer) {
      Support.forEach(buckets, consumer);
    }

    /**
     * Context-passing forEach. Avoids a capturing-lambda allocation — pass a non-capturing {@link
     * BiConsumer} (typically a {@code static final}) plus whatever side-band state it needs.
     */
    public <T> void forEach(T context, BiConsumer<? super T, ? super TEntry> consumer) {
      Support.forEach(buckets, context, consumer);
    }
  }

  /**
   * Building blocks for concurrent hash-table operations, mirroring {@link Hashtable.Support}.
   *
   * <p>Use {@link D1} or {@link D2} when their object-key constraints are acceptable — they handle
   * synchronization internally. Use {@code Support} directly only when you need primitive key
   * components or other entry-level flexibility that {@code D1}/{@code D2} cannot provide.
   *
   * <p><b>Synchronization contract.</b> {@link #bucket} performs a volatile read of the bucket slot
   * and is safe to call from any thread without a lock — this is the lock-free read path. Writes
   * (inserting a new entry) are the caller's responsibility: use the same double-checked locking
   * pattern that {@link D1} and {@link D2} use internally —
   *
   * <ol>
   *   <li>Lock-free pre-check: walk the chain via {@link #bucket}; return if found.
   *   <li>Acquire a lock on a stable object owned by the same class that owns the {@code buckets}
   *       array (typically {@code synchronized (this)}).
   *   <li>Re-check under the lock (another thread may have inserted between step 1 and step 2).
   *   <li>Build the new entry, set its {@code next} via {@link Hashtable.Entry#setNext}, then write
   *       it to the bucket with {@link AtomicReferenceArray#set} (volatile write).
   * </ol>
   *
   * Locking on the {@code AtomicReferenceArray} itself is also valid but no cleaner — pick
   * whichever lock object is most natural for the owning class.
   *
   * <p>One advantage of using {@code Support} directly over {@link D1}/{@link D2} is that the
   * caller controls the lock object, enabling lock striping: shard the lock by bucket index or key
   * hash to reduce write-path contention if profiling shows the single table-level lock is a
   * bottleneck.
   */
  public static final class Support {
    private Support() {}

    public static int bucketIndex(AtomicReferenceArray<Hashtable.Entry> buckets, long keyHash) {
      return (int) (keyHash & (buckets.length() - 1));
    }

    /**
     * Returns the head entry of the bucket that {@code keyHash} maps to, cast to the caller's
     * concrete entry type. The unchecked cast lives here so chain-walk loops at call sites don't
     * need to thread a raw {@link Hashtable.Entry} variable through.
     */
    @SuppressWarnings("unchecked")
    public static <TEntry extends Hashtable.Entry> TEntry bucket(
        AtomicReferenceArray<Hashtable.Entry> buckets, long keyHash) {
      return (TEntry) buckets.get(bucketIndex(buckets, keyHash));
    }

    /**
     * Returns the head entry of the bucket at {@code index}, cast to the caller's concrete entry
     * type. Use when the bucket index is already computed (e.g. inside {@code getOrCreate} where
     * the same index is reused across the lock boundary).
     */
    @SuppressWarnings("unchecked")
    public static <TEntry extends Hashtable.Entry> TEntry bucket(
        AtomicReferenceArray<Hashtable.Entry> buckets, int index) {
      return (TEntry) buckets.get(index);
    }

    @SuppressWarnings("unchecked")
    public static <TEntry extends Hashtable.Entry> void forEach(
        AtomicReferenceArray<Hashtable.Entry> buckets, Consumer<? super TEntry> consumer) {
      for (int i = 0; i < buckets.length(); i++) {
        for (TEntry te = (TEntry) buckets.get(i); te != null; te = te.next()) {
          consumer.accept(te);
        }
      }
    }

    @SuppressWarnings("unchecked")
    public static <T, TEntry extends Hashtable.Entry> void forEach(
        AtomicReferenceArray<Hashtable.Entry> buckets,
        T context,
        BiConsumer<? super T, ? super TEntry> consumer) {
      for (int i = 0; i < buckets.length(); i++) {
        for (TEntry te = (TEntry) buckets.get(i); te != null; te = te.next()) {
          consumer.accept(context, te);
        }
      }
    }
  }
}
