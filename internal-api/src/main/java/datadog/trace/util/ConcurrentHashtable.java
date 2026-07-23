package datadog.trace.util;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Concurrent hash table providing lock-free reads and locked writes for {@link D1} (single-key) and
 * {@link D2} (composite-key) tables.
 *
 * <p>The API deliberately mirrors {@link Hashtable} so the two are familiar to use, but the two
 * share <b>no implementation</b>: {@code ConcurrentHashtable} carries its own {@link Entry}
 * hierarchy with a {@code volatile} chain pointer and its own write paths. The single-threaded and
 * concurrent variants evolve under different constraints (the concurrent one must reason about the
 * memory model on every mutation), so coupling them through a shared base would be a hazard, not a
 * convenience.
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
 * D1#get}/{@link D2#get} begins with a volatile read of the slot. The chain {@code next} pointer is
 * {@code volatile} as well, so every step of a chain walk is a volatile read. This is what makes
 * <em>removal</em> safe: a splice (re-pointing a predecessor's {@code next} past the removed entry,
 * or replacing the bucket head) is a volatile write that lock-free readers observe. The cost is a
 * volatile read per chain step and a slightly more expensive insert; the benefit is that the table
 * supports removal — {@link D1#remove}, {@link D1#removeIf}, {@link D1#drain}, and {@link D1#clear}
 * — rather than being append-only. {@link D1#drain} is the read-and-reset primitive for flush/
 * publish workflows: it removes every entry while handing each to a caller-supplied sink.
 *
 * <p><b>Removal and in-flight readers.</b> A removed entry's own {@code next} pointer is left
 * intact (it is never nulled). A reader that had already advanced onto the entry being removed must
 * still be able to follow {@code next} forward to the rest of the chain; the detached entry is
 * simply unreachable for new lookups and becomes garbage once no in-flight reader references it. A
 * concurrent lookup racing a removal may observe either the pre- or post-removal state — both are
 * valid linearizations.
 */
public final class ConcurrentHashtable {
  private ConcurrentHashtable() {}

  /**
   * Internal base class for concurrent entries. Stores the precomputed 64-bit keyHash and a {@code
   * volatile} chain-next pointer used to link colliding entries within a single bucket.
   *
   * <p>The {@code next} pointer is {@code volatile} (unlike {@link Hashtable.Entry}) so that chain
   * splices performed by {@link D1#remove}/{@link D2#remove} are visible to lock-free readers.
   *
   * <p>Subclasses add the key field(s) and a {@code matches(...)} method tailored to their key
   * arity. See {@link D1.Entry} and {@link D2.Entry}; for higher arities, or for primitive key
   * components, subclass this directly and drive the table mechanics with {@link Support}.
   */
  public abstract static class Entry {
    public final long keyHash;
    private volatile Entry next = null;

    protected Entry(long keyHash) {
      this.keyHash = keyHash;
    }

    public final <TEntry extends Entry> void setNext(TEntry next) {
      this.next = next;
    }

    @SuppressWarnings("unchecked")
    public final <TEntry extends Entry> TEntry next() {
      return (TEntry) this.next;
    }
  }

  /**
   * Single-key concurrent hash table. Lock-free on hit; locked on miss/mutation.
   *
   * @param <K> the key type
   * @param <TEntry> the user's {@link D1.Entry D1.Entry&lt;K&gt;} subclass
   */
  public static final class D1<K, TEntry extends D1.Entry<K>> {

    /**
     * Abstract base for {@link D1} entries. Subclass to add value fields you wish to mutate in
     * place after retrieving the entry via {@link D1#get}.
     *
     * @param <K> the key type
     */
    public abstract static class Entry<K> extends ConcurrentHashtable.Entry {
      final K key;

      protected Entry(K key) {
        super(hash(key));
        this.key = key;
      }

      public boolean matches(Object key) {
        return Objects.equals(this.key, key);
      }

      /**
       * Returns the 64-bit lookup hash for {@code key}. Null keys map to {@link Long#MIN_VALUE} so
       * they don't collide with a real key that hashes to 0; real-key collisions in chains are
       * resolved by {@link #matches(Object)}.
       */
      public static long hash(Object key) {
        return (key == null) ? Long.MIN_VALUE : key.hashCode();
      }
    }

    private final AtomicReferenceArray<ConcurrentHashtable.Entry> buckets;
    private final AtomicInteger size = new AtomicInteger();

    public D1(int capacity) {
      this.buckets = new AtomicReferenceArray<>(Support.sizeFor(capacity));
    }

    public int size() {
      return size.get();
    }

    public TEntry get(K key) {
      long keyHash = D1.Entry.hash(key);
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
      long keyHash = D1.Entry.hash(key);
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

    /**
     * Removes and returns the entry for {@code key}, or {@code null} if absent. Acquires the
     * table-level lock to splice the chain; lock-free readers observe the removal via the volatile
     * write of the predecessor's {@code next} (or the bucket head).
     */
    public TEntry remove(K key) {
      long keyHash = D1.Entry.hash(key);
      int index = Support.bucketIndex(buckets, keyHash);
      synchronized (this) {
        ConcurrentHashtable.Entry prev = null;
        for (TEntry te = Support.bucket(buckets, index); te != null; prev = te, te = te.next()) {
          if (te.keyHash == keyHash && te.matches(key)) {
            Support.unlink(buckets, index, prev, te);
            size.decrementAndGet();
            return te;
          }
        }
        return null;
      }
    }

    /**
     * Removes every entry matching {@code predicate}, returning {@code true} if any were removed.
     * Holds the table-level lock for the whole sweep, so the predicate sees a stable table and
     * concurrent writers are excluded; lock-free readers continue throughout.
     */
    public boolean removeIf(Predicate<? super TEntry> predicate) {
      synchronized (this) {
        return Support.removeIf(buckets, size, predicate);
      }
    }

    /**
     * Removes every entry, passing each removed entry to {@code sink} as it is unlinked — the
     * read-and-reset primitive for flush/publish workflows (drain the table into a telemetry batch,
     * an event emitter, etc.). The whole drain runs under the table-level lock, so it is atomic
     * with respect to other writers; {@code sink} therefore runs under the lock and should be cheap
     * (accumulate into a collection rather than doing heavy work inline). Equivalent to {@code
     * forEach}-then-{@code clear} but in a single locked pass that observes exactly what was
     * removed.
     *
     * <p>A capturing-lambda {@code sink} is fine here — drain is a rare flush operation — but a
     * context-passing overload is offered for callers that prefer to avoid the allocation.
     */
    public void drain(Consumer<? super TEntry> sink) {
      synchronized (this) {
        Support.drain(buckets, sink);
        size.set(0);
      }
    }

    /**
     * Context-passing {@link #drain(Consumer)}. Pass a non-capturing {@link BiConsumer} (typically
     * a {@code static final}) plus the accumulator as {@code context} (e.g. the target list or
     * event builder) to avoid a capturing-lambda allocation.
     */
    public <T> void drain(T context, BiConsumer<? super T, ? super TEntry> sink) {
      synchronized (this) {
        Support.drain(buckets, context, sink);
        size.set(0);
      }
    }

    /** Removes all entries. Lock-free readers mid-walk complete against the entries they hold. */
    public void clear() {
      synchronized (this) {
        Support.clear(buckets);
        size.set(0);
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
   * Two-key (composite-key) concurrent hash table. Lock-free on hit; locked on miss/mutation.
   *
   * <p>Key parts are passed directly to {@link #get} and {@link #getOrCreate}, eliminating the
   * per-lookup composite key object allocation that {@code ConcurrentHashMap<Pair<K1,K2>, V>}
   * requires.
   *
   * @param <K1> first key type
   * @param <K2> second key type
   * @param <TEntry> the user's {@link D2.Entry D2.Entry&lt;K1, K2&gt;} subclass
   */
  public static final class D2<K1, K2, TEntry extends D2.Entry<K1, K2>> {

    /**
     * Abstract base for {@link D2} entries. Subclass to add value fields you wish to mutate in
     * place.
     *
     * @param <K1> first key type
     * @param <K2> second key type
     */
    public abstract static class Entry<K1, K2> extends ConcurrentHashtable.Entry {
      final K1 key1;
      final K2 key2;

      protected Entry(K1 key1, K2 key2) {
        super(hash(key1, key2));
        this.key1 = key1;
        this.key2 = key2;
      }

      public boolean matches(K1 key1, K2 key2) {
        return Objects.equals(this.key1, key1) && Objects.equals(this.key2, key2);
      }

      /** Returns the 64-bit lookup hash combining both key parts via {@link LongHashingUtils}. */
      public static long hash(Object key1, Object key2) {
        return LongHashingUtils.hash(key1, key2);
      }
    }

    private final AtomicReferenceArray<ConcurrentHashtable.Entry> buckets;
    private final AtomicInteger size = new AtomicInteger();

    public D2(int capacity) {
      this.buckets = new AtomicReferenceArray<>(Support.sizeFor(capacity));
    }

    public int size() {
      return size.get();
    }

    public TEntry get(K1 key1, K2 key2) {
      long keyHash = D2.Entry.hash(key1, key2);
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
     * D2.Entry#hash(Object, Object) D2.Entry.hash(key1, key2)}.
     */
    public TEntry getOrCreate(
        K1 key1, K2 key2, BiFunction<? super K1, ? super K2, ? extends TEntry> creator) {
      long keyHash = D2.Entry.hash(key1, key2);
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

    /**
     * Removes and returns the entry for {@code (key1, key2)}, or {@code null} if absent. Acquires
     * the table-level lock to splice the chain; lock-free readers observe the removal via the
     * volatile write of the predecessor's {@code next} (or the bucket head).
     */
    public TEntry remove(K1 key1, K2 key2) {
      long keyHash = D2.Entry.hash(key1, key2);
      int index = Support.bucketIndex(buckets, keyHash);
      synchronized (this) {
        ConcurrentHashtable.Entry prev = null;
        for (TEntry te = Support.bucket(buckets, index); te != null; prev = te, te = te.next()) {
          if (te.keyHash == keyHash && te.matches(key1, key2)) {
            Support.unlink(buckets, index, prev, te);
            size.decrementAndGet();
            return te;
          }
        }
        return null;
      }
    }

    /**
     * Removes every entry matching {@code predicate}, returning {@code true} if any were removed.
     * Holds the table-level lock for the whole sweep, so the predicate sees a stable table and
     * concurrent writers are excluded; lock-free readers continue throughout.
     */
    public boolean removeIf(Predicate<? super TEntry> predicate) {
      synchronized (this) {
        return Support.removeIf(buckets, size, predicate);
      }
    }

    /**
     * Removes every entry, passing each removed entry to {@code sink} as it is unlinked — the
     * read-and-reset primitive for flush/publish workflows (drain the table into a telemetry batch,
     * an event emitter, etc.). The whole drain runs under the table-level lock, so it is atomic
     * with respect to other writers; {@code sink} therefore runs under the lock and should be cheap
     * (accumulate into a collection rather than doing heavy work inline). Equivalent to {@code
     * forEach}-then-{@code clear} but in a single locked pass that observes exactly what was
     * removed.
     *
     * <p>A capturing-lambda {@code sink} is fine here — drain is a rare flush operation — but a
     * context-passing overload is offered for callers that prefer to avoid the allocation.
     */
    public void drain(Consumer<? super TEntry> sink) {
      synchronized (this) {
        Support.drain(buckets, sink);
        size.set(0);
      }
    }

    /**
     * Context-passing {@link #drain(Consumer)}. Pass a non-capturing {@link BiConsumer} (typically
     * a {@code static final}) plus the accumulator as {@code context} (e.g. the target list or
     * event builder) to avoid a capturing-lambda allocation.
     */
    public <T> void drain(T context, BiConsumer<? super T, ? super TEntry> sink) {
      synchronized (this) {
        Support.drain(buckets, context, sink);
        size.set(0);
      }
    }

    /** Removes all entries. Lock-free readers mid-walk complete against the entries they hold. */
    public void clear() {
      synchronized (this) {
        Support.clear(buckets);
        size.set(0);
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
   * Building blocks for concurrent hash-table operations.
   *
   * <p>Use {@link D1} or {@link D2} when their object-key constraints are acceptable — they handle
   * synchronization internally. Use {@code Support} directly only when you need primitive key
   * components or other entry-level flexibility that {@code D1}/{@code D2} cannot provide.
   *
   * <p><b>Read path.</b> {@link #bucket} performs a volatile read of the bucket slot and is safe to
   * call from any thread without a lock; chain {@code next} pointers are volatile, so chain walks
   * are lock-free.
   *
   * <p><b>Write path (insert).</b> Writes are the caller's responsibility. Use the same
   * double-checked locking pattern that {@link D1} and {@link D2} use internally:
   *
   * <ol>
   *   <li>Lock-free pre-check: walk the chain via {@link #bucket}; return if found.
   *   <li>Acquire a lock on a stable object owned by the same class that owns the {@code buckets}
   *       array (typically {@code synchronized (this)}).
   *   <li>Re-check under the lock (another thread may have inserted between step 1 and step 2).
   *   <li>Build the new entry, set its {@code next} via {@link Entry#setNext}, then write it to the
   *       bucket with {@link AtomicReferenceArray#set} (volatile write).
   * </ol>
   *
   * <p><b>Write path (remove).</b> Under the lock, splice the entry out with {@link #unlink}: it
   * re-points the predecessor's {@code next} (or the bucket head) past the removed entry via a
   * volatile write that lock-free readers observe. The removed entry's own {@code next} is left
   * intact so a reader already positioned on it can still traverse forward to the rest of the
   * chain. For full or predicate-driven sweeps, hold the lock and call {@link #removeIf} or {@link
   * #clear}.
   *
   * <p>One advantage of using {@code Support} directly over {@link D1}/{@link D2} is that the
   * caller controls the lock object, enabling lock striping: shard the lock by bucket index or key
   * hash to reduce write-path contention if profiling shows the single table-level lock is a
   * bottleneck.
   */
  public static final class Support {
    private Support() {}

    /**
     * Returns the bucket-array length to allocate for a table sized to hold {@code requestedSize}
     * entries: {@code requestedSize} rounded up to the next power of two.
     */
    public static int sizeFor(int requestedSize) {
      return Hashtable.Support.sizeFor(requestedSize);
    }

    public static int bucketIndex(
        AtomicReferenceArray<ConcurrentHashtable.Entry> buckets, long keyHash) {
      return (int) (keyHash & (buckets.length() - 1));
    }

    /**
     * Returns the head entry of the bucket that {@code keyHash} maps to, cast to the caller's
     * concrete entry type. The unchecked cast lives here so chain-walk loops at call sites don't
     * need to thread a raw {@link Entry} variable through.
     */
    @SuppressWarnings("unchecked")
    public static <TEntry extends ConcurrentHashtable.Entry> TEntry bucket(
        AtomicReferenceArray<ConcurrentHashtable.Entry> buckets, long keyHash) {
      return (TEntry) buckets.get(bucketIndex(buckets, keyHash));
    }

    /**
     * Returns the head entry of the bucket at {@code index}, cast to the caller's concrete entry
     * type. Use when the bucket index is already computed (e.g. inside {@code getOrCreate} where
     * the same index is reused across the lock boundary).
     */
    @SuppressWarnings("unchecked")
    public static <TEntry extends ConcurrentHashtable.Entry> TEntry bucket(
        AtomicReferenceArray<ConcurrentHashtable.Entry> buckets, int index) {
      return (TEntry) buckets.get(index);
    }

    /**
     * Splices {@code entry} out of the chain at {@code index}. {@code prev} is the in-chain
     * predecessor, or {@code null} when {@code entry} is the bucket head. Re-points the predecessor
     * (or the bucket head slot) past {@code entry} via a volatile write so lock-free readers see
     * the removal. {@code entry}'s own {@code next} is deliberately left intact so a reader already
     * positioned on it can still traverse forward. Must be called under the table's write lock;
     * does not touch size accounting.
     */
    public static void unlink(
        AtomicReferenceArray<ConcurrentHashtable.Entry> buckets,
        int index,
        ConcurrentHashtable.Entry prev,
        ConcurrentHashtable.Entry entry) {
      ConcurrentHashtable.Entry next = entry.next();
      if (prev == null) {
        buckets.set(index, next);
      } else {
        prev.setNext(next);
      }
    }

    /**
     * Removes every entry matching {@code predicate} from {@code buckets}, decrementing {@code
     * size} once per removal. Must be called under the table's write lock.
     */
    @SuppressWarnings("unchecked")
    public static <TEntry extends ConcurrentHashtable.Entry> boolean removeIf(
        AtomicReferenceArray<ConcurrentHashtable.Entry> buckets,
        AtomicInteger size,
        Predicate<? super TEntry> predicate) {
      boolean removed = false;
      for (int i = 0; i < buckets.length(); i++) {
        ConcurrentHashtable.Entry prev = null;
        for (ConcurrentHashtable.Entry e = buckets.get(i); e != null; e = e.next()) {
          if (predicate.test((TEntry) e)) {
            unlink(buckets, i, prev, e);
            size.decrementAndGet();
            removed = true;
            // prev stays put: e is now unlinked, so the last survivor remains the predecessor.
          } else {
            prev = e;
          }
        }
      }
      return removed;
    }

    /**
     * Removes every entry, passing each to {@code sink} as its bucket is cleared. Each bucket head
     * is nulled (a volatile write that publishes the removal) before its chain is fed to {@code
     * sink}, so new readers see an empty bucket while the detached chain — whose {@code next}
     * pointers stay intact — is handed to the caller. Must be called under the table's write lock;
     * does not touch size accounting.
     */
    @SuppressWarnings("unchecked")
    public static <TEntry extends ConcurrentHashtable.Entry> void drain(
        AtomicReferenceArray<ConcurrentHashtable.Entry> buckets, Consumer<? super TEntry> sink) {
      for (int i = 0; i < buckets.length(); i++) {
        ConcurrentHashtable.Entry head = buckets.get(i);
        if (head == null) {
          continue;
        }
        buckets.set(i, null);
        for (ConcurrentHashtable.Entry e = head; e != null; e = e.next()) {
          sink.accept((TEntry) e);
        }
      }
    }

    /** Context-passing variant of {@link #drain(AtomicReferenceArray, Consumer)}. */
    @SuppressWarnings("unchecked")
    public static <T, TEntry extends ConcurrentHashtable.Entry> void drain(
        AtomicReferenceArray<ConcurrentHashtable.Entry> buckets,
        T context,
        BiConsumer<? super T, ? super TEntry> sink) {
      for (int i = 0; i < buckets.length(); i++) {
        ConcurrentHashtable.Entry head = buckets.get(i);
        if (head == null) {
          continue;
        }
        buckets.set(i, null);
        for (ConcurrentHashtable.Entry e = head; e != null; e = e.next()) {
          sink.accept(context, (TEntry) e);
        }
      }
    }

    /** Nulls every bucket head. Must be called under the table's write lock. */
    public static void clear(AtomicReferenceArray<ConcurrentHashtable.Entry> buckets) {
      for (int i = 0; i < buckets.length(); i++) {
        buckets.set(i, null);
      }
    }

    @SuppressWarnings("unchecked")
    public static <TEntry extends ConcurrentHashtable.Entry> void forEach(
        AtomicReferenceArray<ConcurrentHashtable.Entry> buckets,
        Consumer<? super TEntry> consumer) {
      for (int i = 0; i < buckets.length(); i++) {
        for (TEntry te = (TEntry) buckets.get(i); te != null; te = te.next()) {
          consumer.accept(te);
        }
      }
    }

    @SuppressWarnings("unchecked")
    public static <T, TEntry extends ConcurrentHashtable.Entry> void forEach(
        AtomicReferenceArray<ConcurrentHashtable.Entry> buckets,
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
