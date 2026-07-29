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
 *
 * <p><b>Custom tables (higher arity / primitive keys).</b> Use {@link D1} or {@link D2} when their
 * object-key constraints are acceptable — they handle synchronization internally. When you need
 * primitive key components, three-or-more key parts, or extra per-entry value fields, drive the
 * table yourself with the static building blocks on this class: allocate the spine with {@link
 * #createFixedBuckets(Class, int)}, then operate on it with {@link #bucket}, {@link #unlink},
 * {@link #removeIf}, {@link #drain}, {@link #clear}, and {@link #forEach}. This is the same "static
 * functions over a caller-owned array" shape as {@link Hashtable} (see how {@code AggregateTable}
 * uses {@code Hashtable}); the calling class then owns the array and exposes whatever operations it
 * needs. Subclass {@link Entry} directly for such tables.
 *
 * <p><b>Locking model.</b> Writes are guarded by a per-table monitor obtained from {@link
 * #getWriteLock(AtomicReferenceArray)} — treat it as opaque rather than assuming it is the array.
 * Reads are lock-free: {@link #bucket} walks and {@link #forEach} take no lock and are safe from
 * any thread. The whole-table mutators — {@link #removeIf}, {@link #drain}, {@link #clear} — are
 * <b>self-locking</b> ({@code synchronized (getWriteLock(buckets))} internally), so a custom table
 * calls them directly with no lock of its own. The only writes a custom table performs by hand are
 * single-key insert and remove; each is an atomic check-then-write that the caller wraps in {@code
 * synchronized (getWriteLock(buckets))} so it excludes other writers and the self-locking mutators
 * (same monitor, so it nests cleanly with the built-ins):
 *
 * <ol>
 *   <li>Lock-free pre-check: walk the chain via {@link #bucket}; return if found.
 *   <li>{@code synchronized (getWriteLock(buckets))} — take the table's write monitor.
 *   <li>Re-check under the lock (another thread may have inserted between step 1 and step 2).
 *   <li>Insert: build the entry and publish it with {@link #insertHeadEntry}. Remove: splice it out
 *       with {@link #unlink}. Both are volatile writes that lock-free readers observe atomically.
 * </ol>
 *
 * <p>{@link #bucket} (a lock-free read), {@link #insertHeadEntry}, and {@link #unlink} are the
 * single-slot primitives for that hand-written path; the two mutating ones do <b>not</b> lock, so
 * call them only inside the caller's {@code synchronized (getWriteLock(buckets))} block. The
 * entry's chain pointer is written for you by those helpers — custom tables never touch it
 * directly.
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
   * components, subclass this directly and drive the table with the static building blocks on
   * {@link ConcurrentHashtable}.
   */
  public abstract static class Entry {
    public final long keyHash;
    private volatile Entry next = null;

    protected Entry(long keyHash) {
      this.keyHash = keyHash;
    }

    // Package-private: the only writers are the static insert/remove building blocks
    // (insertHeadEntry, unlink) on the enclosing class, which reach it via the Entry bound. Custom
    // tables mutate chains through those helpers, never by touching next directly.
    final <TEntry extends Entry> void setNext(TEntry next) {
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

      /** The key this entry was created with. */
      public K key() {
        return this.key;
      }

      public boolean matches(Object key) {
        // equals() on the lookup param, not the field, so the JIT can devirtualize it once
        // matches() inlines into get/getOrCreate (the caller's key type is known there).
        return Objects.equals(key, this.key);
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

    private final AtomicReferenceArray<TEntry> buckets;
    private final AtomicInteger size = new AtomicInteger();

    private D1(AtomicReferenceArray<TEntry> buckets) {
      this.buckets = buckets;
    }

    /**
     * Creates a single-key table with a fixed bucket count sized for {@code capacity} entries. The
     * {@code entryClass} pins the concrete entry type so the compiler infers both {@code K} and
     * {@code TEntry} at the call site — e.g. {@code D1.createFixedBuckets(MyEntry.class, 64)} — and
     * keeps the factory symmetric with the rest of the flat-collections family (see {@link
     * ConcurrentHashtable#createFixedBuckets(Class, int)} for why the class isn't otherwise
     * consumed here). Capacity is fixed; the table does not resize.
     */
    public static <K, TEntry extends D1.Entry<K>> D1<K, TEntry> createFixedBuckets(
        Class<TEntry> entryClass, int capacity) {
      return new D1<>(ConcurrentHashtable.createFixedBuckets(entryClass, capacity));
    }

    public int size() {
      return size.get();
    }

    public TEntry get(K key) {
      long keyHash = D1.Entry.hash(key);
      for (TEntry te = bucket(buckets, keyHash); te != null; te = te.next()) {
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
      int index = bucketIndex(buckets, keyHash);
      for (TEntry te = bucket(buckets, index); te != null; te = te.next()) {
        if (te.keyHash == keyHash && te.matches(key)) {
          return te;
        }
      }
      synchronized (getWriteLock(buckets)) {
        for (TEntry te = bucket(buckets, index); te != null; te = te.next()) {
          if (te.keyHash == keyHash && te.matches(key)) {
            return te;
          }
        }
        TEntry newEntry = creator.apply(key);
        insertHeadEntry(buckets, index, newEntry);
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
      int index = bucketIndex(buckets, keyHash);
      synchronized (getWriteLock(buckets)) {
        TEntry prev = null;
        for (TEntry te = bucket(buckets, index); te != null; prev = te, te = te.next()) {
          if (te.keyHash == keyHash && te.matches(key)) {
            unlink(buckets, index, prev, te);
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
      return ConcurrentHashtable.removeIf(buckets, size, predicate);
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
     *
     * <p><b>Contract:</b> {@code sink} must not throw. Entries are detached as the sweep proceeds
     * and {@code size} is reset only after it completes, so a {@code sink} that throws part-way
     * leaves those already-detached entries gone while {@code size()} still reports the pre-drain
     * count. The drain is not rolled back; a throwing sink is a caller error that also means a
     * half-published flush. This is intentional — the alternative is per-entry size bookkeeping on
     * a path that only matters when the caller is already in error.
     */
    public void drain(Consumer<? super TEntry> sink) {
      synchronized (getWriteLock(buckets)) {
        ConcurrentHashtable.drain(buckets, sink);
        size.set(0);
      }
    }

    /**
     * Context-passing {@link #drain(Consumer)}. Pass a non-capturing {@link BiConsumer} (typically
     * a {@code static final}) plus the accumulator as {@code context} (e.g. the target list or
     * event builder) to avoid a capturing-lambda allocation.
     */
    public <C> void drain(C context, BiConsumer<? super C, ? super TEntry> sink) {
      synchronized (getWriteLock(buckets)) {
        ConcurrentHashtable.drain(buckets, context, sink);
        size.set(0);
      }
    }

    /** Removes all entries. Lock-free readers mid-walk complete against the entries they hold. */
    public void clear() {
      synchronized (getWriteLock(buckets)) {
        ConcurrentHashtable.clear(buckets);
        size.set(0);
      }
    }

    public void forEach(Consumer<? super TEntry> consumer) {
      ConcurrentHashtable.forEach(buckets, consumer);
    }

    /**
     * Context-passing forEach. Avoids a capturing-lambda allocation — pass a non-capturing {@link
     * BiConsumer} (typically a {@code static final}) plus whatever side-band state it needs.
     */
    public <C> void forEach(C context, BiConsumer<? super C, ? super TEntry> consumer) {
      ConcurrentHashtable.forEach(buckets, context, consumer);
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

      /** The first key part this entry was created with. */
      public K1 key1() {
        return this.key1;
      }

      /** The second key part this entry was created with. */
      public K2 key2() {
        return this.key2;
      }

      public boolean matches(K1 key1, K2 key2) {
        // equals() on the lookup params, not the fields, so the JIT can devirtualize them once
        // matches() inlines into get/getOrCreate (the caller's key types are known there).
        return Objects.equals(key1, this.key1) && Objects.equals(key2, this.key2);
      }

      /** Returns the 64-bit lookup hash combining both key parts via {@link LongHashingUtils}. */
      public static long hash(Object key1, Object key2) {
        return LongHashingUtils.hash(key1, key2);
      }
    }

    private final AtomicReferenceArray<TEntry> buckets;
    private final AtomicInteger size = new AtomicInteger();

    private D2(AtomicReferenceArray<TEntry> buckets) {
      this.buckets = buckets;
    }

    /**
     * Creates a composite-key table with a fixed bucket count sized for {@code capacity} entries.
     * The {@code entryClass} pins the concrete entry type so the compiler infers {@code K1}, {@code
     * K2}, and {@code TEntry} at the call site — e.g. {@code D2.createFixedBuckets(MyEntry.class,
     * 64)} — and keeps the factory symmetric with the rest of the flat-collections family (see
     * {@link ConcurrentHashtable#createFixedBuckets(Class, int)} for why the class isn't otherwise
     * consumed here). Capacity is fixed; the table does not resize.
     */
    public static <K1, K2, TEntry extends D2.Entry<K1, K2>> D2<K1, K2, TEntry> createFixedBuckets(
        Class<TEntry> entryClass, int capacity) {
      return new D2<>(ConcurrentHashtable.createFixedBuckets(entryClass, capacity));
    }

    public int size() {
      return size.get();
    }

    public TEntry get(K1 key1, K2 key2) {
      long keyHash = D2.Entry.hash(key1, key2);
      for (TEntry te = bucket(buckets, keyHash); te != null; te = te.next()) {
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
      int index = bucketIndex(buckets, keyHash);
      for (TEntry te = bucket(buckets, index); te != null; te = te.next()) {
        if (te.keyHash == keyHash && te.matches(key1, key2)) {
          return te;
        }
      }
      synchronized (getWriteLock(buckets)) {
        for (TEntry te = bucket(buckets, index); te != null; te = te.next()) {
          if (te.keyHash == keyHash && te.matches(key1, key2)) {
            return te;
          }
        }
        TEntry newEntry = creator.apply(key1, key2);
        insertHeadEntry(buckets, index, newEntry);
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
      int index = bucketIndex(buckets, keyHash);
      synchronized (getWriteLock(buckets)) {
        TEntry prev = null;
        for (TEntry te = bucket(buckets, index); te != null; prev = te, te = te.next()) {
          if (te.keyHash == keyHash && te.matches(key1, key2)) {
            unlink(buckets, index, prev, te);
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
      return ConcurrentHashtable.removeIf(buckets, size, predicate);
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
     *
     * <p><b>Contract:</b> {@code sink} must not throw. Entries are detached as the sweep proceeds
     * and {@code size} is reset only after it completes, so a {@code sink} that throws part-way
     * leaves those already-detached entries gone while {@code size()} still reports the pre-drain
     * count. The drain is not rolled back; a throwing sink is a caller error that also means a
     * half-published flush. This is intentional — the alternative is per-entry size bookkeeping on
     * a path that only matters when the caller is already in error.
     */
    public void drain(Consumer<? super TEntry> sink) {
      synchronized (getWriteLock(buckets)) {
        ConcurrentHashtable.drain(buckets, sink);
        size.set(0);
      }
    }

    /**
     * Context-passing {@link #drain(Consumer)}. Pass a non-capturing {@link BiConsumer} (typically
     * a {@code static final}) plus the accumulator as {@code context} (e.g. the target list or
     * event builder) to avoid a capturing-lambda allocation.
     */
    public <C> void drain(C context, BiConsumer<? super C, ? super TEntry> sink) {
      synchronized (getWriteLock(buckets)) {
        ConcurrentHashtable.drain(buckets, context, sink);
        size.set(0);
      }
    }

    /** Removes all entries. Lock-free readers mid-walk complete against the entries they hold. */
    public void clear() {
      synchronized (getWriteLock(buckets)) {
        ConcurrentHashtable.clear(buckets);
        size.set(0);
      }
    }

    public void forEach(Consumer<? super TEntry> consumer) {
      ConcurrentHashtable.forEach(buckets, consumer);
    }

    /**
     * Context-passing forEach. Avoids a capturing-lambda allocation — pass a non-capturing {@link
     * BiConsumer} (typically a {@code static final}) plus whatever side-band state it needs.
     */
    public <C> void forEach(C context, BiConsumer<? super C, ? super TEntry> consumer) {
      ConcurrentHashtable.forEach(buckets, context, consumer);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Static building blocks over a caller-owned bucket array (formerly the nested Support class).
  // Use these to assemble a custom table (higher arity, primitive keys, extra value fields) when
  // D1/D2 don't fit; D1/D2 delegate to them internally. The whole-table mutators (removeIf, drain,
  // clear) self-lock on the array; the single-slot write primitives (insertHeadEntry, unlink) do
  // not lock and must be called under the caller's own synchronized (getWriteLock(buckets)) block.
  // Readers
  // (bucket walks, forEach) are lock-free.
  // ---------------------------------------------------------------------------------------------

  /**
   * Allocates a fixed-size bucket array sized to hold {@code capacity} entries: {@code capacity}
   * rounded up to the next power of two.
   *
   * <p>Unlike {@code FlatHashtable}, whose open-addressing spine is a genuine {@code E[]} that must
   * be reflectively allocated from {@code entryClass}, the concurrent spine is an {@link
   * AtomicReferenceArray} whose element type is erased — so {@code entryClass} is <b>not</b> used
   * to allocate here. It is accepted purely to (a) keep the factory symmetric with the rest of the
   * flat-collections family and (b) act as a type-inference anchor so callers write {@code
   * createFixedBuckets(MyEntry.class, n)} and get back a precisely typed {@code
   * AtomicReferenceArray<MyEntry>} without an explicit witness.
   */
  public static <TEntry extends Entry> AtomicReferenceArray<TEntry> createFixedBuckets(
      Class<TEntry> entryClass, int capacity) {
    return new AtomicReferenceArray<>(sizeFor(capacity));
  }

  /**
   * Returns the bucket-array length to allocate for a table sized to hold {@code requestedSize}
   * entries: {@code requestedSize} rounded up to the next power of two. Shares {@link Hashtable}'s
   * sizing so the two families round identically.
   */
  public static int sizeFor(int requestedSize) {
    return Hashtable.Support.sizeFor(requestedSize);
  }

  /**
   * Returns the monitor that guards writes to {@code buckets}. A custom table locks on this —
   * {@code synchronized (getWriteLock(buckets)) { … }} — around its scan-then-insert/remove so it
   * excludes other writers and the self-locking whole-table mutators (they lock on the same
   * monitor, so the blocks nest). Treat the returned object as <b>opaque</b>: it happens to be the
   * array today, but obtain it here rather than assuming that, so callers stay correct if the
   * monitor ever changes.
   */
  public static Object getWriteLock(AtomicReferenceArray<?> buckets) {
    return buckets;
  }

  public static int bucketIndex(AtomicReferenceArray<?> buckets, long keyHash) {
    return (int) (keyHash & (buckets.length() - 1));
  }

  /**
   * Returns the head entry of the bucket that {@code keyHash} maps to. The bucket read is a
   * volatile read of the slot, so it is safe from any thread without a lock.
   */
  public static <TEntry extends Entry> TEntry bucket(
      AtomicReferenceArray<TEntry> buckets, long keyHash) {
    return buckets.get(bucketIndex(buckets, keyHash));
  }

  /**
   * Returns the head entry of the bucket at {@code index}. Use when the bucket index is already
   * computed (e.g. inside {@code getOrCreate} where the same index is reused across the lock
   * boundary).
   */
  public static <TEntry extends Entry> TEntry bucket(
      AtomicReferenceArray<TEntry> buckets, int index) {
    return buckets.get(index);
  }

  /**
   * Splices {@code entry} in as the new head of the chain at {@code index}, publishing it with a
   * volatile {@link AtomicReferenceArray#set} so lock-free readers observe the whole entry (its
   * {@code next} already points at the old head) atomically. Single-slot primitive: it does not
   * lock, so call it inside the caller's {@code synchronized (getWriteLock(buckets))} block, after
   * re-checking the chain for the key under that lock. Does not touch size accounting.
   */
  public static <TEntry extends Entry> void insertHeadEntry(
      AtomicReferenceArray<TEntry> buckets, int index, TEntry entry) {
    assert Thread.holdsLock(getWriteLock(buckets))
        : "insertHeadEntry called without holding getWriteLock(buckets)";
    entry.setNext(buckets.get(index));
    buckets.set(index, entry);
  }

  /**
   * Convenience overload of {@link #insertHeadEntry(AtomicReferenceArray, int, Entry)} that derives
   * the bucket index from {@code keyHash}. Prefer the int-taking overload when the index is already
   * computed (e.g. a {@code getOrCreate} that reuses it across the lock-free pre-check).
   */
  public static <TEntry extends Entry> void insertHeadEntry(
      AtomicReferenceArray<TEntry> buckets, long keyHash, TEntry entry) {
    insertHeadEntry(buckets, bucketIndex(buckets, keyHash), entry);
  }

  /**
   * Splices {@code entry} out of the chain at {@code index}. {@code prev} is the in-chain
   * predecessor, or {@code null} when {@code entry} is the bucket head. Re-points the predecessor
   * (or the bucket head slot) past {@code entry} via a volatile write so lock-free readers see the
   * removal. {@code entry}'s own {@code next} is deliberately left intact so a reader already
   * positioned on it can still traverse forward. This is a single-slot primitive: it does not lock,
   * so call it inside the caller's {@code synchronized (getWriteLock(buckets))} block. Does not
   * touch size accounting.
   */
  public static <TEntry extends Entry> void unlink(
      AtomicReferenceArray<TEntry> buckets, int index, TEntry prev, TEntry entry) {
    assert Thread.holdsLock(getWriteLock(buckets))
        : "unlink called without holding getWriteLock(buckets)";
    TEntry next = entry.next();
    if (prev == null) {
      buckets.set(index, next);
    } else {
      prev.setNext(next);
    }
  }

  /**
   * Removes every entry matching {@code predicate} from {@code buckets}, decrementing {@code size}
   * once per removal. Self-locking: synchronizes on {@code buckets} for the whole sweep, so the
   * predicate sees a stable table and concurrent writers are excluded; lock-free readers continue
   * throughout.
   */
  public static <TEntry extends Entry> boolean removeIf(
      AtomicReferenceArray<TEntry> buckets,
      AtomicInteger size,
      Predicate<? super TEntry> predicate) {
    synchronized (getWriteLock(buckets)) {
      boolean removed = false;
      for (int i = 0; i < buckets.length(); i++) {
        TEntry prev = null;
        for (TEntry e = buckets.get(i); e != null; e = e.next()) {
          if (predicate.test(e)) {
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
  }

  /**
   * Removes every entry, passing each to {@code sink} as its bucket is cleared. Each bucket head is
   * nulled (a volatile write that publishes the removal) before its chain is fed to {@code sink},
   * so new readers see an empty bucket while the detached chain — whose {@code next} pointers stay
   * intact — is handed to the caller. Self-locking: synchronizes on {@code buckets} for the whole
   * pass. Does not touch size accounting, so a caller tracking size resets it inside its own {@code
   * synchronized (getWriteLock(buckets))} block (which nests with this one on the same monitor).
   *
   * <p>{@code sink} must not throw: buckets are detached as the sweep proceeds, so a sink that
   * throws part-way leaves earlier buckets drained and later ones intact, and any caller-side size
   * reset never runs. The drain is not rolled back — a throwing sink is a caller error.
   */
  public static <TEntry extends Entry> void drain(
      AtomicReferenceArray<TEntry> buckets, Consumer<? super TEntry> sink) {
    synchronized (getWriteLock(buckets)) {
      for (int i = 0; i < buckets.length(); i++) {
        TEntry head = buckets.get(i);
        if (head == null) {
          continue;
        }
        buckets.set(i, null);
        for (TEntry e = head; e != null; e = e.next()) {
          sink.accept(e);
        }
      }
    }
  }

  /** Context-passing variant of {@link #drain(AtomicReferenceArray, Consumer)}. Self-locking. */
  public static <C, TEntry extends Entry> void drain(
      AtomicReferenceArray<TEntry> buckets, C context, BiConsumer<? super C, ? super TEntry> sink) {
    synchronized (getWriteLock(buckets)) {
      for (int i = 0; i < buckets.length(); i++) {
        TEntry head = buckets.get(i);
        if (head == null) {
          continue;
        }
        buckets.set(i, null);
        for (TEntry e = head; e != null; e = e.next()) {
          sink.accept(context, e);
        }
      }
    }
  }

  /** Nulls every bucket head. Self-locking: synchronizes on {@code buckets}. */
  public static void clear(AtomicReferenceArray<?> buckets) {
    synchronized (getWriteLock(buckets)) {
      for (int i = 0; i < buckets.length(); i++) {
        buckets.set(i, null);
      }
    }
  }

  public static <TEntry extends Entry> void forEach(
      AtomicReferenceArray<TEntry> buckets, Consumer<? super TEntry> consumer) {
    for (int i = 0; i < buckets.length(); i++) {
      for (TEntry te = buckets.get(i); te != null; te = te.next()) {
        consumer.accept(te);
      }
    }
  }

  public static <C, TEntry extends Entry> void forEach(
      AtomicReferenceArray<TEntry> buckets,
      C context,
      BiConsumer<? super C, ? super TEntry> consumer) {
    for (int i = 0; i < buckets.length(); i++) {
      for (TEntry te = buckets.get(i); te != null; te = te.next()) {
        consumer.accept(context, te);
      }
    }
  }
}
