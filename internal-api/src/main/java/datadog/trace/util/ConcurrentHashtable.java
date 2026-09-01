package datadog.trace.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Fixed-capacity concurrent hash tables with lock-free reads and serialized writes.
 *
 * <p>{@link D1} accepts one key. {@link D2} accepts two key parts directly. Both store
 * caller-defined {@link Entry} objects in separate-chained buckets and never resize.
 *
 * <p>Bucket heads live in an {@link AtomicReferenceArray}. Its volatile {@code set}/{@code get}
 * semantics publish an inserted entry and its initialized fields to lock-free readers. The volatile
 * {@code next} links likewise make chain splices visible. A read racing a removal may observe
 * either state; removed entries retain their link so a reader already on the chain can still finish
 * traversing it.
 *
 * <p>{@link D2} structurally avoids a temporary composite key. With a conventional concurrent map,
 * that wrapper may be retained on insertion, so HotSpot cannot reliably scalar-replace it through
 * escape analysis. Avoiding the wrapper matters on the tracer's hot lookup paths.
 *
 * <p>{@link D1} and {@link D2} manage locking and capacity internally. For primitive or
 * higher-arity keys, subclass {@link Entry} and use the static helpers. {@link #bucketFor}, {@link
 * #bucketAt}, and {@link #forEach} are lock-free. {@link #removeIf}, {@link #drain}, and {@link
 * #clear} acquire the table write lock internally. Follow each mutation helper's locking contract
 * and treat the monitor returned by the lock helpers as opaque.
 *
 * <h2>Choosing between the three tables</h2>
 *
 * <ol>
 *   <li><b>Concurrent access?</b> Use this class -- the only thread-safe one of the three. {@code
 *       FlatHashtable} is racy by design, and {@code Hashtable} is not thread-safe at all.
 *   <li><b>Otherwise: does the population reset wholesale, or evolve?</b> A table cleared as a unit
 *       -- once per cycle, per request, or built and then discarded -- wants {@code FlatHashtable},
 *       whose open addressing has no tombstones and so offers no removal beyond clearing. A table
 *       whose entries come and go independently wants the chained {@code Hashtable}, which removes
 *       and evicts in place.
 * </ol>
 *
 * <p>Lifetime is the usual shorthand for that second question and mostly works, because a
 * short-lived table never needs to remove -- it just dies. The case it mis-sorts is a long-lived
 * table that resets on a cycle: that is a sequence of short lives, and belongs with the short-lived
 * ones. Compare a table that evicts stale entries one at a time while the busy ones survive the
 * cycle (evolving -- {@code Hashtable}) against one that clears every entry each time it reports
 * (resets -- {@code FlatHashtable}).
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
    @Nullable
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
  @ThreadSafe
  public static final class D1<K, TEntry extends D1.Entry<K>> {

    /**
     * Abstract base for {@link D1} entries. Subclass to add value fields you wish to mutate in
     * place after retrieving the entry via {@link D1#get}.
     *
     * @param <K> the key type
     */
    public abstract static class Entry<K> extends ConcurrentHashtable.Entry {
      final K key;

      protected Entry(@Nullable K key) {
        super(hash(key));
        this.key = key;
      }

      /** The key this entry was created with. */
      @Nullable
      public K key() {
        return this.key;
      }

      public boolean matches(@Nullable Object key) {
        // equals() on the lookup param, not the field, so the JIT can devirtualize it once
        // matches() inlines into get/getOrCreate (the caller's key type is known there).
        return Objects.equals(key, this.key);
      }

      /**
       * Returns the 64-bit lookup hash for {@code key}. Null keys map to {@link Long#MIN_VALUE} so
       * they don't collide with a real key that hashes to 0; real-key collisions in chains are
       * resolved by {@link #matches(Object)}.
       */
      public static long hash(@Nullable Object key) {
        return (key == null) ? Long.MIN_VALUE : key.hashCode();
      }
    }

    private final State<TEntry> state;

    private D1(State<TEntry> state) {
      this.state = state;
    }

    /**
     * Creates a fixed-size table holding at most {@code maxCapacity} entries. {@code entryClass} is
     * used only to infer the concrete entry type; entries are created by the functions passed to
     * the insertion methods. The table does not resize.
     */
    @Nonnull
    public static <K, TEntry extends D1.Entry<K>> D1<K, TEntry> createBounded(
        @Nonnull Class<TEntry> entryClass, int maxCapacity) {
      return new D1<>(State.createBounded(entryClass, maxCapacity));
    }

    public int size() {
      return state.sizeManager.estimateSize();
    }

    public boolean isFull() {
      return state.sizeManager.isFull();
    }

    @Nullable
    public TEntry get(@Nullable K key) {
      long keyHash = D1.Entry.hash(key);
      for (TEntry curEntry = bucketFor(state, keyHash);
          curEntry != null;
          curEntry = curEntry.next()) {
        if (curEntry.keyHash == keyHash && curEntry.matches(key)) {
          return curEntry;
        }
      }
      return null;
    }

    /**
     * Returns the entry for {@code key}, creating one via {@code creator} if absent and the table
     * is under capacity. Lock-free on hit; acquires a table-level lock on miss. Wraps {@link
     * #tryGetOrCreateOrNull} — see that method for the refusal and ordering details.
     */
    @Nonnull
    public Maybe<TEntry> tryGetOrCreate(
        @Nullable K key, @Nonnull Function<? super K, ? extends TEntry> creator) {
      return Maybe.of(tryGetOrCreateOrNull(key, creator));
    }

    /**
     * Escape hatch for {@link #tryGetOrCreate} for callers that want the nullable entry directly
     * rather than a {@link Maybe} wrapper. Returns {@code null} when the table is at capacity and
     * {@code key} was not already present. Re-checks under the lock to avoid duplicate entries
     * under concurrent misses.
     */
    @Nullable
    public TEntry tryGetOrCreateOrNull(
        @Nullable K key, @Nonnull Function<? super K, ? extends TEntry> creator) {
      long keyHash = D1.Entry.hash(key);
      int index = bucketIndex(state.buckets, keyHash);
      for (TEntry curEntry = bucketAt(state, index); curEntry != null; curEntry = curEntry.next()) {
        if (curEntry.keyHash == keyHash && curEntry.matches(key)) {
          return curEntry;
        }
      }
      synchronized (getTableWriteLock(state)) {
        for (TEntry curEntry = bucketAt(state, index);
            curEntry != null;
            curEntry = curEntry.next()) {
          if (curEntry.keyHash == keyHash && curEntry.matches(key)) {
            return curEntry;
          }
        }
        // isFull() is checked before creating the entry, not before reserving a slot for it:
        // creator.apply() can throw, and if we'd already reserved (incremented) the slot, a
        // throwing creator would leak that reservation forever. So we accept the entry only after
        // creator succeeds, then increment.
        if (state.sizeManager.isFull()) {
          return null;
        }
        TEntry newEntry = creator.apply(key);
        insertHeadEntryAt(state, index, newEntry);
        state.sizeManager.increment();
        return newEntry;
      }
    }

    /**
     * {@link #tryGetOrCreate}, but when the table is full, evicts one entry matching {@code
     * evictable} to make room instead of refusing the insert. Refuses only when the table is full
     * <em>and</em> nothing matches {@code evictable} — see {@link #tryGetOrCreateOrEvictOrNull} for
     * the null-returning form and the eviction/creation ordering.
     */
    @Nonnull
    public Maybe<TEntry> tryGetOrCreateOrEvict(
        @Nullable K key,
        @Nonnull Function<? super K, ? extends TEntry> creator,
        @Nonnull Predicate<? super TEntry> evictable) {
      return Maybe.of(tryGetOrCreateOrEvictOrNull(key, creator, evictable));
    }

    /**
     * Escape hatch for {@link #tryGetOrCreateOrEvict} for callers that want the nullable entry
     * directly. Eviction runs before {@code creator}, not after: {@code creator} may throw, so
     * freeing a slot and only then attempting the fallible create keeps a thrown exception from
     * ever leaving a slot double-booked. A creator that throws after a successful eviction simply
     * leaves the table one entry smaller — no corruption, just a wasted eviction.
     */
    @Nullable
    public TEntry tryGetOrCreateOrEvictOrNull(
        @Nullable K key,
        @Nonnull Function<? super K, ? extends TEntry> creator,
        @Nonnull Predicate<? super TEntry> evictable) {
      long keyHash = D1.Entry.hash(key);
      int index = bucketIndex(state.buckets, keyHash);
      for (TEntry curEntry = bucketAt(state, index); curEntry != null; curEntry = curEntry.next()) {
        if (curEntry.keyHash == keyHash && curEntry.matches(key)) {
          return curEntry;
        }
      }
      synchronized (getTableWriteLock(state)) {
        for (TEntry curEntry = bucketAt(state, index);
            curEntry != null;
            curEntry = curEntry.next()) {
          if (curEntry.keyHash == keyHash && curEntry.matches(key)) {
            return curEntry;
          }
        }
        if (state.sizeManager.isFull()
            && state.sizeManager.evictOne(state.buckets, evictable) == null) {
          return null;
        }
        TEntry newEntry = creator.apply(key);
        insertHeadEntryAt(state, index, newEntry);
        state.sizeManager.increment();
        return newEntry;
      }
    }

    /**
     * Removes and returns the entry for {@code key}, or {@code null} if absent. Acquires the
     * table-level lock to splice the chain; lock-free readers observe the removal via the volatile
     * write of the predecessor's {@code next} (or the bucket head).
     */
    @Nullable
    public TEntry remove(@Nullable K key) {
      long keyHash = D1.Entry.hash(key);
      int index = bucketIndex(state.buckets, keyHash);
      synchronized (getTableWriteLock(state)) {
        TEntry prev = null;
        for (TEntry curEntry = bucketAt(state, index);
            curEntry != null;
            prev = curEntry, curEntry = curEntry.next()) {
          if (curEntry.keyHash == keyHash && curEntry.matches(key)) {
            unlink(state, index, prev, curEntry);
            state.sizeManager.decrement();
            return curEntry;
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
    public boolean removeIf(@Nonnull Predicate<? super TEntry> predicate) {
      return ConcurrentHashtable.removeIf(state, predicate);
    }

    /**
     * Removes all entries and passes each one to {@code sink} while holding the table write lock.
     * The sink should be quick and must not throw. If it throws, the partial drain is not rolled
     * back and the size is not adjusted.
     *
     * <p>Use {@link #drain(Object, BiConsumer)} to avoid a capturing lambda.
     */
    public void drain(@Nonnull Consumer<? super TEntry> sink) {
      ConcurrentHashtable.drain(state, sink);
    }

    /**
     * Context-passing {@link #drain(Consumer)}. Pass a non-capturing {@link BiConsumer} (typically
     * a {@code static final}) plus the accumulator as {@code context} (e.g. the target list or
     * event builder) to avoid a capturing-lambda allocation.
     */
    public <C> void drain(C context, @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
      ConcurrentHashtable.drain(state, context, sink);
    }

    /** Removes all entries. Lock-free readers mid-walk complete against the entries they hold. */
    public void clear() {
      ConcurrentHashtable.clear(state);
    }

    public void forEach(@Nonnull Consumer<? super TEntry> consumer) {
      ConcurrentHashtable.forEach(state, consumer);
    }

    /**
     * Context-passing forEach. Avoids a capturing-lambda allocation — pass a non-capturing {@link
     * BiConsumer} (typically a {@code static final}) plus whatever side-band state it needs.
     */
    public <C> void forEach(C context, @Nonnull BiConsumer<? super C, ? super TEntry> consumer) {
      ConcurrentHashtable.forEach(state, context, consumer);
    }
  }

  /**
   * Two-key concurrent hash table. Key parts are passed directly to {@link #get} and {@link
   * #tryGetOrCreate}, avoiding a composite wrapper whose allocation would otherwise rely on HotSpot
   * escape analysis to disappear. Reads are lock-free; misses and mutations acquire the write lock.
   *
   * @param <K1> first key type
   * @param <K2> second key type
   * @param <TEntry> the user's {@link D2.Entry D2.Entry&lt;K1, K2&gt;} subclass
   */
  @ThreadSafe
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

      protected Entry(@Nullable K1 key1, @Nullable K2 key2) {
        super(hash(key1, key2));
        this.key1 = key1;
        this.key2 = key2;
      }

      /** The first key part this entry was created with. */
      @Nullable
      public K1 key1() {
        return this.key1;
      }

      /** The second key part this entry was created with. */
      @Nullable
      public K2 key2() {
        return this.key2;
      }

      public boolean matches(@Nullable K1 key1, @Nullable K2 key2) {
        // equals() on the lookup params, not the fields, so the JIT can devirtualize them once
        // matches() inlines into get/getOrCreate (the caller's key types are known there).
        return Objects.equals(key1, this.key1) && Objects.equals(key2, this.key2);
      }

      /** Returns the 64-bit lookup hash combining both key parts via {@link LongHashingUtils}. */
      public static long hash(@Nullable Object key1, @Nullable Object key2) {
        return LongHashingUtils.hash(key1, key2);
      }
    }

    private final State<TEntry> state;

    private D2(State<TEntry> state) {
      this.state = state;
    }

    /**
     * Creates a fixed-size table holding at most {@code maxCapacity} entries. {@code entryClass} is
     * used only to infer the concrete entry type; entries are created by the functions passed to
     * the insertion methods. The table does not resize.
     */
    @Nonnull
    public static <K1, K2, TEntry extends D2.Entry<K1, K2>> D2<K1, K2, TEntry> createBounded(
        @Nonnull Class<TEntry> entryClass, int maxCapacity) {
      return new D2<>(State.createBounded(entryClass, maxCapacity));
    }

    public int size() {
      return state.sizeManager.estimateSize();
    }

    public boolean isFull() {
      return state.sizeManager.isFull();
    }

    @Nullable
    public TEntry get(@Nullable K1 key1, @Nullable K2 key2) {
      long keyHash = D2.Entry.hash(key1, key2);
      for (TEntry curEntry = bucketFor(state, keyHash);
          curEntry != null;
          curEntry = curEntry.next()) {
        if (curEntry.keyHash == keyHash && curEntry.matches(key1, key2)) {
          return curEntry;
        }
      }
      return null;
    }

    /**
     * Returns the entry for {@code (key1, key2)}, creating one via {@code creator} if absent and
     * the table is under capacity. Lock-free on hit; acquires a table-level lock on miss. Wraps
     * {@link #tryGetOrCreateOrNull} — see that method for the refusal and ordering details.
     *
     * <p>The {@code creator} should build an entry whose {@code keyHash} equals {@link
     * D2.Entry#hash(Object, Object) D2.Entry.hash(key1, key2)}.
     */
    @Nonnull
    public Maybe<TEntry> tryGetOrCreate(
        @Nullable K1 key1,
        @Nullable K2 key2,
        @Nonnull BiFunction<? super K1, ? super K2, ? extends TEntry> creator) {
      return Maybe.of(tryGetOrCreateOrNull(key1, key2, creator));
    }

    /**
     * Escape hatch for {@link #tryGetOrCreate} for callers that want the nullable entry directly
     * rather than a {@link Maybe} wrapper. Returns {@code null} when the table is at capacity and
     * {@code (key1, key2)} was not already present. Re-checks under the lock to avoid duplicate
     * entries under concurrent misses.
     */
    @Nullable
    public TEntry tryGetOrCreateOrNull(
        @Nullable K1 key1,
        @Nullable K2 key2,
        @Nonnull BiFunction<? super K1, ? super K2, ? extends TEntry> creator) {
      long keyHash = D2.Entry.hash(key1, key2);
      int index = bucketIndex(state.buckets, keyHash);
      for (TEntry curEntry = bucketAt(state, index); curEntry != null; curEntry = curEntry.next()) {
        if (curEntry.keyHash == keyHash && curEntry.matches(key1, key2)) {
          return curEntry;
        }
      }
      synchronized (getTableWriteLock(state)) {
        for (TEntry curEntry = bucketAt(state, index);
            curEntry != null;
            curEntry = curEntry.next()) {
          if (curEntry.keyHash == keyHash && curEntry.matches(key1, key2)) {
            return curEntry;
          }
        }
        // isFull() is checked before creating the entry, not before reserving a slot for it:
        // creator.apply() can throw, and if we'd already reserved (incremented) the slot, a
        // throwing creator would leak that reservation forever. So we accept the entry only after
        // creator succeeds, then increment.
        if (state.sizeManager.isFull()) {
          return null;
        }
        TEntry newEntry = creator.apply(key1, key2);
        insertHeadEntryAt(state, index, newEntry);
        state.sizeManager.increment();
        return newEntry;
      }
    }

    /**
     * {@link #tryGetOrCreate}, but when the table is full, evicts one entry matching {@code
     * evictable} to make room instead of refusing the insert. Refuses only when the table is full
     * <em>and</em> nothing matches {@code evictable} — see {@link #tryGetOrCreateOrEvictOrNull} for
     * the null-returning form and the eviction/creation ordering.
     */
    @Nonnull
    public Maybe<TEntry> tryGetOrCreateOrEvict(
        @Nullable K1 key1,
        @Nullable K2 key2,
        @Nonnull BiFunction<? super K1, ? super K2, ? extends TEntry> creator,
        @Nonnull Predicate<? super TEntry> evictable) {
      return Maybe.of(tryGetOrCreateOrEvictOrNull(key1, key2, creator, evictable));
    }

    /**
     * Escape hatch for {@link #tryGetOrCreateOrEvict} for callers that want the nullable entry
     * directly. Eviction runs before {@code creator}, not after: {@code creator} may throw, so
     * freeing a slot and only then attempting the fallible create keeps a thrown exception from
     * ever leaving a slot double-booked. A creator that throws after a successful eviction simply
     * leaves the table one entry smaller — no corruption, just a wasted eviction.
     */
    @Nullable
    public TEntry tryGetOrCreateOrEvictOrNull(
        @Nullable K1 key1,
        @Nullable K2 key2,
        @Nonnull BiFunction<? super K1, ? super K2, ? extends TEntry> creator,
        @Nonnull Predicate<? super TEntry> evictable) {
      long keyHash = D2.Entry.hash(key1, key2);
      int index = bucketIndex(state.buckets, keyHash);
      for (TEntry curEntry = bucketAt(state, index); curEntry != null; curEntry = curEntry.next()) {
        if (curEntry.keyHash == keyHash && curEntry.matches(key1, key2)) {
          return curEntry;
        }
      }
      synchronized (getTableWriteLock(state)) {
        for (TEntry curEntry = bucketAt(state, index);
            curEntry != null;
            curEntry = curEntry.next()) {
          if (curEntry.keyHash == keyHash && curEntry.matches(key1, key2)) {
            return curEntry;
          }
        }
        if (state.sizeManager.isFull()
            && state.sizeManager.evictOne(state.buckets, evictable) == null) {
          return null;
        }
        TEntry newEntry = creator.apply(key1, key2);
        insertHeadEntryAt(state, index, newEntry);
        state.sizeManager.increment();
        return newEntry;
      }
    }

    /**
     * Removes and returns the entry for {@code (key1, key2)}, or {@code null} if absent. Acquires
     * the table-level lock to splice the chain; lock-free readers observe the removal via the
     * volatile write of the predecessor's {@code next} (or the bucket head).
     */
    @Nullable
    public TEntry remove(@Nullable K1 key1, @Nullable K2 key2) {
      long keyHash = D2.Entry.hash(key1, key2);
      int index = bucketIndex(state.buckets, keyHash);
      synchronized (getTableWriteLock(state)) {
        TEntry prev = null;
        for (TEntry curEntry = bucketAt(state, index);
            curEntry != null;
            prev = curEntry, curEntry = curEntry.next()) {
          if (curEntry.keyHash == keyHash && curEntry.matches(key1, key2)) {
            unlink(state, index, prev, curEntry);
            state.sizeManager.decrement();
            return curEntry;
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
    public boolean removeIf(@Nonnull Predicate<? super TEntry> predicate) {
      return ConcurrentHashtable.removeIf(state, predicate);
    }

    /**
     * Removes all entries and passes each one to {@code sink} while holding the table write lock.
     * The sink should be quick and must not throw. If it throws, the partial drain is not rolled
     * back and the size is not adjusted.
     *
     * <p>Use {@link #drain(Object, BiConsumer)} to avoid a capturing lambda.
     */
    public void drain(@Nonnull Consumer<? super TEntry> sink) {
      ConcurrentHashtable.drain(state, sink);
    }

    /**
     * Context-passing {@link #drain(Consumer)}. Pass a non-capturing {@link BiConsumer} (typically
     * a {@code static final}) plus the accumulator as {@code context} (e.g. the target list or
     * event builder) to avoid a capturing-lambda allocation.
     */
    public <C> void drain(C context, @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
      ConcurrentHashtable.drain(state, context, sink);
    }

    /** Removes all entries. Lock-free readers mid-walk complete against the entries they hold. */
    public void clear() {
      ConcurrentHashtable.clear(state);
    }

    public void forEach(@Nonnull Consumer<? super TEntry> consumer) {
      ConcurrentHashtable.forEach(state, consumer);
    }

    /**
     * Context-passing forEach. Avoids a capturing-lambda allocation — pass a non-capturing {@link
     * BiConsumer} (typically a {@code static final}) plus whatever side-band state it needs.
     */
    public <C> void forEach(C context, @Nonnull BiConsumer<? super C, ? super TEntry> consumer) {
      ConcurrentHashtable.forEach(state, context, consumer);
    }
  }

  /**
   * Tracks a capped table's occupancy and eviction position.
   *
   * <p>The count includes live entries and outstanding reservations. Its {@link AtomicInteger}
   * provides volatile visibility and atomic updates, allowing size queries and {@link
   * #tryReserve()} without the table lock. The plain {@code evictionCursor} is instead protected by
   * the table write lock; methods annotated with {@link GuardedBy} require that lock.
   */
  @ThreadSafe
  public static final class SizeManager {
    private final AtomicInteger size = new AtomicInteger();
    private final int capacity;

    /**
     * Bucket index the last eviction removed from. The next scan resumes here, so a sustained
     * eviction stream doesn't repeatedly re-walk the same hot entries clustered near bucket 0.
     */
    @GuardedBy("getTableWriteLock(buckets)")
    private int evictionCursor;

    public SizeManager(int capacity) {
      this.capacity = capacity;
    }

    /** Live entries. Safe to call without the write lock. */
    public int estimateSize() {
      return size.get();
    }

    public int capacity() {
      return capacity;
    }

    /** {@code true} once {@link #estimateSize()} has reached {@link #capacity()}. */
    public boolean isFull() {
      return size.get() >= capacity;
    }

    /**
     * Atomically reserves one slot without taking the table write lock. It claims first with {@link
     * AtomicInteger#incrementAndGet()} and refunds values above capacity, so concurrent callers
     * cannot both acquire the last slot. Returns {@code false} with the count unchanged when the
     * table is full.
     *
     * <p>Build the entry before reserving: there is no cancellation operation, so abandoning a
     * successful reservation permanently consumes capacity.
     */
    public boolean tryReserve() {
      if (size.incrementAndGet() > capacity) {
        size.decrementAndGet();
        return false;
      }
      return true;
    }

    /**
     * Reserves one slot, evicting an entry matching {@code evictable} when the table is full.
     * Returns {@code false} without changing the table when no entry can be evicted.
     *
     * <p>The reservation survives concurrent drain and clear operations. The caller must fill it;
     * an abandoned reservation permanently consumes capacity.
     */
    @GuardedBy("getTableWriteLock(buckets)")
    public <TEntry extends Entry> boolean tryReserveOrEvict(
        @Nonnull AtomicReferenceArray<TEntry> buckets,
        @Nonnull Predicate<? super TEntry> evictable) {
      if (tryReserve()) {
        return true;
      }
      if (evictOne(buckets, evictable) == null) {
        return false;
      }
      // evictOne already decremented; the slot it freed is ours.
      size.incrementAndGet();
      return true;
    }

    /** Call after successfully linking a new entry. */
    public void increment() {
      size.incrementAndGet();
    }

    /** Call after successfully unlinking an entry. */
    public void decrement() {
      size.decrementAndGet();
    }

    /**
     * Releases {@code removed} slots after a sweep and resets the {@code evictionCursor}.
     * Outstanding reservations remain counted.
     */
    @GuardedBy("getTableWriteLock(buckets)")
    @SuppressFBWarnings(
        value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
        justification =
            "evictionCursor is read and written only under synchronized (getTableWriteLock(buckets)); SpotBugs"
                + " cannot model that dynamic guard")
    public void release(int removed) {
      if (removed != 0) {
        size.addAndGet(-removed);
      }
      evictionCursor = 0;
    }

    /**
     * Removes and returns the first entry matching {@code evictable}, scanning from the previous
     * eviction position and wrapping once. Returns {@code null} without changing the count when no
     * entry matches.
     *
     * <p>This operation may inspect every live entry while holding the table write lock, so the
     * predicate should be quick.
     */
    @GuardedBy("getTableWriteLock(buckets)")
    @Nullable
    public <TEntry extends Entry> TEntry evictOne(
        @Nonnull AtomicReferenceArray<TEntry> buckets,
        @Nonnull Predicate<? super TEntry> evictable) {
      TEntry evicted = evictOneInRange(buckets, evictable, evictionCursor, buckets.length());
      if (evicted == null && evictionCursor != 0) {
        evicted = evictOneInRange(buckets, evictable, 0, evictionCursor);
      }
      if (evicted != null) {
        size.decrementAndGet();
        return evicted;
      }
      // Nothing matched anywhere; step the evictionCursor on regardless so repeated refusals don't
      // all
      // restart the (wasted) scan from the same bucket.
      evictionCursor = bucketIndex(buckets, evictionCursor + 1);
      return null;
    }

    @GuardedBy("getTableWriteLock(buckets)")
    @SuppressFBWarnings(
        value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
        justification =
            "evictionCursor is read and written only under synchronized (getTableWriteLock(buckets)); SpotBugs"
                + " cannot model that dynamic guard")
    @Nullable
    private <TEntry extends Entry> TEntry evictOneInRange(
        @Nonnull AtomicReferenceArray<TEntry> buckets,
        @Nonnull Predicate<? super TEntry> evictable,
        int startBucket,
        int endBucket) {
      for (int i = startBucket; i < endBucket; i++) {
        TEntry prev = null;
        for (TEntry e = buckets.get(i); e != null; e = e.next()) {
          if (evictable.test(e)) {
            unlink(buckets, i, prev, e);
            evictionCursor = i;
            return e;
          }
          prev = e;
        }
      }
      return null;
    }

    /**
     * Unlinks every entry matching {@code evictable} in one full pass, decrementing the count for
     * each, and returns how many were removed. Resets the scan position, since a full pass leaves
     * nothing later to resume from.
     */
    @GuardedBy("getTableWriteLock(buckets)")
    @SuppressFBWarnings(
        value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
        justification =
            "evictionCursor is read and written only under synchronized (getTableWriteLock(buckets)); SpotBugs"
                + " cannot model that dynamic guard")
    public <TEntry extends Entry> int evictAll(
        @Nonnull AtomicReferenceArray<TEntry> buckets,
        @Nonnull Predicate<? super TEntry> evictable) {
      int count = 0;
      for (int i = 0; i < buckets.length(); i++) {
        TEntry prev = null;
        for (TEntry e = buckets.get(i); e != null; e = e.next()) {
          if (evictable.test(e)) {
            unlink(buckets, i, prev, e);
            size.decrementAndGet();
            count++;
          } else {
            prev = e;
          }
        }
      }
      evictionCursor = 0;
      return count;
    }
  }

  /**
   * Bucket array and occupancy manager for a caller-defined capped table. Keep them paired and
   * prefer the {@code State}-accepting helpers so structural changes update the count consistently.
   */
  public static final class State<TEntry extends Entry> {
    public final AtomicReferenceArray<TEntry> buckets;
    public final SizeManager sizeManager;

    private State(AtomicReferenceArray<TEntry> buckets, int maxCapacity) {
      this.buckets = buckets;
      this.sizeManager = new SizeManager(maxCapacity);
    }

    /**
     * Creates a bucket array for {@code maxCapacity} entries and pairs it with a manager enforcing
     * that cap. {@code entryClass} is used only to infer {@code TEntry}.
     */
    @Nonnull
    public static <TEntry extends Entry> State<TEntry> createBounded(
        @Nonnull Class<TEntry> entryClass, int maxCapacity) {
      return new State<>(createFixedBuckets(entryClass, maxCapacity), maxCapacity);
    }
  }

  /** Live entries in {@code state}; see {@link SizeManager#estimateSize()}. Lock-free. */
  public static int estimateSize(@Nonnull State<?> state) {
    return state.sizeManager.estimateSize();
  }

  /**
   * {@code true} once {@code state} is at capacity; see {@link SizeManager#isFull()}. Lock-free.
   */
  public static boolean isFull(@Nonnull State<?> state) {
    return state.sizeManager.isFull();
  }

  /**
   * Reserves one slot in {@code state}, evicting an entry matching {@code evictable} when
   * necessary. Returns {@code false} if the table is full and nothing can be evicted. This method
   * acquires the table write lock.
   *
   * <p>The reservation survives drain and clear operations. Complete it with {@link
   * #insertReserved}; abandoning it permanently consumes capacity.
   */
  public static <TEntry extends Entry> boolean tryReserveOrEvict(
      @Nonnull State<TEntry> state, @Nonnull Predicate<? super TEntry> evictable) {
    synchronized (getTableWriteLock(state)) {
      return state.sizeManager.tryReserveOrEvict(state.buckets, evictable);
    }
  }

  /**
   * Unlinks the first entry in {@code state} matching {@code evictable}, resuming from where the
   * last eviction looked, and decrements the count. {@code null} if nothing matched anywhere.
   * Self-locking.
   */
  @Nullable
  public static <TEntry extends Entry> TEntry evictOne(
      @Nonnull State<TEntry> state, @Nonnull Predicate<? super TEntry> evictable) {
    synchronized (getTableWriteLock(state)) {
      return state.sizeManager.evictOne(state.buckets, evictable);
    }
  }

  /**
   * Unlinks every entry in {@code state} matching {@code evictable}, decrementing per removal, and
   * returns how many went. Self-locking.
   */
  public static <TEntry extends Entry> int evictAll(
      @Nonnull State<TEntry> state, @Nonnull Predicate<? super TEntry> evictable) {
    synchronized (getTableWriteLock(state)) {
      return state.sizeManager.evictAll(state.buckets, evictable);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Static building blocks over a caller-owned bucket array (formerly the nested Support class).
  // Use these to assemble a custom table (higher arity, primitive keys, extra value fields) when
  // D1/D2 don't fit; D1/D2 delegate to them internally. The whole-table mutators (removeIf, drain,
  // clear) self-lock on the array; the single-slot write primitives (insertHeadEntry, unlink) do
  // not lock and must be called under the caller's own synchronized (getWriteLock(buckets,
  // keyHash)) block.
  // Readers
  // (bucket walks, forEach) are lock-free.
  // ---------------------------------------------------------------------------------------------

  /**
   * Creates a bucket array whose length is {@link #sizeFor(int) sizeFor(capacity)}. Because {@link
   * AtomicReferenceArray}'s element type is erased at runtime, {@code entryClass} is not used for
   * reflective allocation or runtime type checks; it only lets the compiler infer {@code TEntry}.
   */
  @Nonnull
  public static <TEntry extends Entry> AtomicReferenceArray<TEntry> createFixedBuckets(
      @Nonnull Class<TEntry> entryClass, int capacity) {
    return new AtomicReferenceArray<>(sizeFor(capacity));
  }

  /** Upper bound on the bucket-array length returned by {@link #sizeFor(int)}. */
  static final int MAX_BUCKETS = 1 << 30;

  /**
   * Returns the bucket-array length to allocate for a table sized to hold {@code requestedSize}
   * entries: {@code requestedSize} rounded up to the next power of two, capped at {@link
   * #MAX_BUCKETS}. Throws {@link IllegalArgumentException} for negative inputs or inputs above the
   * cap.
   */
  public static int sizeFor(int requestedSize) {
    if (requestedSize < 0) {
      throw new IllegalArgumentException("requestedSize must be non-negative: " + requestedSize);
    }
    if (requestedSize > MAX_BUCKETS) {
      throw new IllegalArgumentException(
          "requestedSize exceeds maximum bucket count (" + MAX_BUCKETS + "): " + requestedSize);
    }
    if (requestedSize <= 1) {
      return 1;
    }
    return Integer.highestOneBit(requestedSize - 1) << 1;
  }

  /**
   * Returns the opaque monitor guarding writes to the bucket selected by {@code keyHash}. Use this
   * monitor for a keyed scan-and-mutate operation; do not depend on its identity or granularity.
   *
   * @see #getWriteLockAt(AtomicReferenceArray, int)
   * @see #getTableWriteLock(AtomicReferenceArray)
   */
  @Nonnull
  public static Object getWriteLock(@Nonnull AtomicReferenceArray<?> buckets, long keyHash) {
    return getWriteLockAt(buckets, bucketIndex(buckets, keyHash));
  }

  /** {@link #getWriteLock(AtomicReferenceArray, long)} over a {@link State}. */
  @Nonnull
  public static Object getWriteLock(@Nonnull State<?> state, long keyHash) {
    return getWriteLock(state.buckets, keyHash);
  }

  /**
   * {@link #getWriteLock(AtomicReferenceArray, long)} for a bucket index that has already been
   * computed — the shape a {@code getOrCreate} wants when it reuses the index from its lock-free
   * pre-check. This is the primitive; the {@code keyHash} form maps the hash through {@link
   * #bucketIndex} and calls it.
   */
  @Nonnull
  public static Object getWriteLockAt(@Nonnull AtomicReferenceArray<?> buckets, int bucketIndex) {
    return buckets;
  }

  /** {@link #getWriteLockAt(AtomicReferenceArray, int)} over a {@link State}. */
  @Nonnull
  public static Object getWriteLockAt(@Nonnull State<?> state, int bucketIndex) {
    return getWriteLockAt(state.buckets, bucketIndex);
  }

  /**
   * Returns the opaque monitor guarding operations that span all buckets. Whole-table helpers such
   * as {@link #drain}, {@link #clear}, and {@link #removeIf} acquire this monitor internally.
   */
  @Nonnull
  public static Object getTableWriteLock(@Nonnull AtomicReferenceArray<?> buckets) {
    return buckets;
  }

  /** {@link #getTableWriteLock(AtomicReferenceArray)} over a {@link State}. */
  @Nonnull
  public static Object getTableWriteLock(@Nonnull State<?> state) {
    return getTableWriteLock(state.buckets);
  }

  public static int bucketIndex(@Nonnull AtomicReferenceArray<?> buckets, long keyHash) {
    // Bucket lengths are powers of two, so masking replaces a more expensive modulo operation.
    return (int) (keyHash & (buckets.length() - 1));
  }

  /**
   * Returns the head entry of the bucket that {@code keyHash} maps to. The bucket read is a
   * volatile read of the slot, so it is safe from any thread without a lock.
   *
   * <p>Named distinctly from {@link #bucketAt} (rather than overloaded on {@code long} vs. {@code
   * int}) deliberately: a caller with a primitive {@code int}-typed key hash that called an
   * overloaded {@code bucket(buckets, intHash)} would silently bind to the {@code int}-index
   * overload instead of widening to this one, reading the raw hash as an array index — out-of-range
   * hashes throw {@link IndexOutOfBoundsException}, in-range-but-wrong ones silently read the wrong
   * bucket.
   */
  @Nullable
  public static <TEntry extends Entry> TEntry bucketFor(
      @Nonnull AtomicReferenceArray<TEntry> buckets, long keyHash) {
    return buckets.get(bucketIndex(buckets, keyHash));
  }

  /** {@link #bucketFor(AtomicReferenceArray, long)} over a {@link State}. */
  @Nullable
  public static <TEntry extends Entry> TEntry bucketFor(
      @Nonnull State<TEntry> state, long keyHash) {
    return bucketFor(state.buckets, keyHash);
  }

  /**
   * Returns the head entry of the bucket at {@code index}. Use when the bucket index is already
   * computed (e.g. inside {@code getOrCreate} where the same index is reused across the lock
   * boundary). See {@link #bucketFor} for why this is a distinct name rather than an {@code int}
   * overload of it.
   */
  @Nullable
  public static <TEntry extends Entry> TEntry bucketAt(
      @Nonnull AtomicReferenceArray<TEntry> buckets, int index) {
    return buckets.get(index);
  }

  /** {@link #bucketAt(AtomicReferenceArray, int)} over a {@link State}. */
  @Nullable
  public static <TEntry extends Entry> TEntry bucketAt(@Nonnull State<TEntry> state, int index) {
    return bucketAt(state.buckets, index);
  }

  /**
   * Publishes {@code entry} as the head of bucket {@code index}. The helper writes the entry's
   * {@code next} link before the volatile {@link AtomicReferenceArray#set}; a volatile bucket read
   * that observes the new head also sees the initialized entry and its link. The caller must hold
   * {@link #getWriteLockAt}; this method does not acquire a lock or update size accounting.
   *
   * <p>The entry must be unlinked and must not be reused after removal. Removal intentionally
   * retains its {@code next} link for readers already traversing that chain.
   */
  @GuardedBy("getWriteLockAt(buckets, index)")
  public static <TEntry extends Entry> void insertHeadEntryAt(
      @Nonnull AtomicReferenceArray<TEntry> buckets, int index, @Nonnull TEntry entry) {
    assert Thread.holdsLock(getWriteLockAt(buckets, index))
        : "insertHeadEntryAt called without holding getWriteLockAt(buckets, index)";
    assert entry.next() == null
        : "Entry already linked -- inserting the same Entry instance twice corrupts the chain"
            + " (unlink() deliberately leaves a removed entry's next intact for in-flight"
            + " readers, so a removed entry must never be reinserted)";
    entry.setNext(buckets.get(index));
    buckets.set(index, entry);
  }

  /** {@link #insertHeadEntryAt(AtomicReferenceArray, int, Entry)} over a {@link State}. */
  @GuardedBy("getWriteLockAt(state, index)")
  public static <TEntry extends Entry> void insertHeadEntryAt(
      @Nonnull State<TEntry> state, int index, @Nonnull TEntry entry) {
    insertHeadEntryAt(state.buckets, index, entry);
  }

  /**
   * Convenience form of {@link #insertHeadEntryAt} that derives the bucket index from {@code
   * keyHash}. Prefer {@link #insertHeadEntryAt} when the index is already computed (e.g. a {@code
   * getOrCreate} that reuses it across the lock-free pre-check).
   */
  @GuardedBy("getWriteLock(buckets, keyHash)")
  public static <TEntry extends Entry> void insertHeadEntryFor(
      @Nonnull AtomicReferenceArray<TEntry> buckets, long keyHash, @Nonnull TEntry entry) {
    insertHeadEntryAt(buckets, bucketIndex(buckets, keyHash), entry);
  }

  /**
   * Links an already-built entry after a successful {@link #tryReserveOrEvict} or {@link
   * SizeManager#tryReserve()} call. This method does not acquire a lock or update the count.
   *
   * <p>Complete all fallible work before reserving. There is no cancellation operation, so an
   * abandoned reservation permanently consumes capacity. Drain and clear do not cancel outstanding
   * reservations.
   */
  @GuardedBy("getTableWriteLock(state)")
  public static <TEntry extends Entry> void insertReserved(
      @Nonnull State<TEntry> state, long keyHash, @Nonnull TEntry entry) {
    insertHeadEntryFor(state.buckets, keyHash, entry);
  }

  /**
   * Splices {@code entry} out of the chain at {@code index}. {@code prev} is the in-chain
   * predecessor, or {@code null} when {@code entry} is the bucket head. Re-points the predecessor
   * (or the bucket head slot) past {@code entry} via a volatile write so lock-free readers see the
   * removal. {@code entry}'s own {@code next} is deliberately left intact so a reader already
   * positioned on it can still traverse forward. This is a single-slot primitive: it does not lock,
   * so call it inside the caller's {@code synchronized (getWriteLockAt(buckets, index))} block.
   * Does not touch size accounting.
   */
  @GuardedBy("getWriteLockAt(buckets, index)")
  public static <TEntry extends Entry> void unlink(
      @Nonnull AtomicReferenceArray<TEntry> buckets,
      int index,
      @Nullable TEntry prev,
      @Nonnull TEntry entry) {
    assert Thread.holdsLock(getWriteLockAt(buckets, index))
        : "unlink called without holding getWriteLockAt(buckets, index)";
    TEntry next = entry.next();
    if (prev == null) {
      buckets.set(index, next);
    } else {
      prev.setNext(next);
    }
  }

  /** {@link #unlink(AtomicReferenceArray, int, Entry, Entry)} over a {@link State}. */
  @GuardedBy("getWriteLockAt(state, index)")
  public static <TEntry extends Entry> void unlink(
      @Nonnull State<TEntry> state, int index, @Nullable TEntry prev, @Nonnull TEntry entry) {
    unlink(state.buckets, index, prev, entry);
  }

  /**
   * Removes every entry matching {@code predicate} from {@code buckets}, decrementing {@code size}
   * once per removal. Self-locking: synchronizes on {@code buckets} for the whole sweep, so the
   * predicate sees a stable table and concurrent writers are excluded; lock-free readers continue
   * throughout.
   */
  public static <TEntry extends Entry> boolean removeIf(
      @Nonnull AtomicReferenceArray<TEntry> buckets,
      @Nonnull AtomicInteger size,
      @Nonnull Predicate<? super TEntry> predicate) {
    synchronized (getTableWriteLock(buckets)) {
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
   * {@link #removeIf(AtomicReferenceArray, AtomicInteger, Predicate)} variant for callers tracking
   * occupancy with a {@link State} instead of a bare counter — used by {@link D1#removeIf} and
   * {@link D2#removeIf}.
   */
  public static <TEntry extends Entry> boolean removeIf(
      @Nonnull State<TEntry> state, @Nonnull Predicate<? super TEntry> predicate) {
    AtomicReferenceArray<TEntry> buckets = state.buckets;
    synchronized (getTableWriteLock(state)) {
      boolean removed = false;
      for (int i = 0; i < buckets.length(); i++) {
        TEntry prev = null;
        for (TEntry e = buckets.get(i); e != null; e = e.next()) {
          if (predicate.test(e)) {
            unlink(buckets, i, prev, e);
            state.sizeManager.decrement();
            removed = true;
          } else {
            prev = e;
          }
        }
      }
      return removed;
    }
  }

  /**
   * Removes all entries while holding the table write lock. Each bucket head is cleared with a
   * volatile write before its detached chain is passed to {@code sink}, so subsequent lock-free
   * readers observe an empty bucket while readers already on that chain can continue through its
   * retained {@code next} links. This overload does not update size accounting.
   *
   * <p>The sink must not throw. If it does, the partial drain is not rolled back.
   */
  public static <TEntry extends Entry> void drain(
      @Nonnull AtomicReferenceArray<TEntry> buckets, @Nonnull Consumer<? super TEntry> sink) {
    drainCounting(buckets, sink);
  }

  /**
   * {@link #drain(AtomicReferenceArray, Consumer)} returning how many entries it handed to {@code
   * sink}, so a {@link State} form can subtract exactly that from its {@link SizeManager} instead
   * of zeroing. The count is free here: the sweep already visits every entry.
   */
  private static <TEntry extends Entry> int drainCounting(
      @Nonnull AtomicReferenceArray<TEntry> buckets, @Nonnull Consumer<? super TEntry> sink) {
    int removed = 0;
    synchronized (getTableWriteLock(buckets)) {
      for (int i = 0; i < buckets.length(); i++) {
        TEntry head = buckets.get(i);
        if (head == null) {
          continue;
        }
        buckets.set(i, null);
        for (TEntry e = head; e != null; e = e.next()) {
          removed++;
          sink.accept(e);
        }
      }
    }
    return removed;
  }

  /** Context-passing variant of {@link #drain(AtomicReferenceArray, Consumer)}. Self-locking. */
  public static <C, TEntry extends Entry> void drain(
      @Nonnull AtomicReferenceArray<TEntry> buckets,
      C context,
      @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
    drainCounting(buckets, context, sink);
  }

  /** {@link #drainCounting(AtomicReferenceArray, Consumer)}, context-passing form. */
  private static <C, TEntry extends Entry> int drainCounting(
      @Nonnull AtomicReferenceArray<TEntry> buckets,
      C context,
      @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
    int removed = 0;
    synchronized (getTableWriteLock(buckets)) {
      for (int i = 0; i < buckets.length(); i++) {
        TEntry head = buckets.get(i);
        if (head == null) {
          continue;
        }
        buckets.set(i, null);
        for (TEntry e = head; e != null; e = e.next()) {
          removed++;
          sink.accept(context, e);
        }
      }
    }
    return removed;
  }

  /**
   * {@link #drain(AtomicReferenceArray, Consumer)} plus the matching bookkeeping: empties {@code
   * state} into {@code sink} and gives its {@link SizeManager} back exactly the slots the sweep
   * freed. Draining without that leaves the cap permanently consumed, so the two belong in one call
   * rather than as a pair the caller has to remember.
   */
  public static <TEntry extends Entry> void drain(
      @Nonnull State<TEntry> state, @Nonnull Consumer<? super TEntry> sink) {
    synchronized (getTableWriteLock(state)) {
      state.sizeManager.release(drainCounting(state.buckets, sink));
    }
  }

  /** Context-passing form of {@link #drain(State, Consumer)}. */
  public static <C, TEntry extends Entry> void drain(
      @Nonnull State<TEntry> state,
      C context,
      @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
    synchronized (getTableWriteLock(state)) {
      state.sizeManager.release(drainCounting(state.buckets, context, sink));
    }
  }

  /** Nulls every bucket head. Self-locking: synchronizes on {@code buckets}. */
  public static void clear(@Nonnull AtomicReferenceArray<?> buckets) {
    synchronized (getTableWriteLock(buckets)) {
      for (int i = 0; i < buckets.length(); i++) {
        buckets.set(i, null);
      }
    }
  }

  /**
   * {@link #clear(AtomicReferenceArray)} returning how many entries it detached, so a {@link State}
   * form can subtract exactly that rather than zeroing — see {@link SizeManager#release(int)} for
   * why that distinction matters. Unlike the plain form this walks the chains, making it O(entries)
   * rather than O(buckets); clear is a rare, whole-table operation, so the walk is affordable and
   * keeping the count honest is worth more than the constant.
   */
  private static int clearCounting(@Nonnull AtomicReferenceArray<? extends Entry> buckets) {
    int removed = 0;
    synchronized (getTableWriteLock(buckets)) {
      for (int i = 0; i < buckets.length(); i++) {
        Entry head = buckets.get(i);
        if (head == null) {
          continue;
        }
        buckets.set(i, null);
        for (Entry e = head; e != null; e = e.next()) {
          removed++;
        }
      }
    }
    return removed;
  }

  /**
   * {@link #clear(AtomicReferenceArray)} over a {@link State}: also resets its {@link SizeManager}.
   */
  public static void clear(@Nonnull State<?> state) {
    synchronized (getTableWriteLock(state)) {
      state.sizeManager.release(clearCounting(state.buckets));
    }
  }

  public static <TEntry extends Entry> void forEach(
      @Nonnull AtomicReferenceArray<TEntry> buckets, @Nonnull Consumer<? super TEntry> consumer) {
    for (int i = 0; i < buckets.length(); i++) {
      for (TEntry curEntry = buckets.get(i); curEntry != null; curEntry = curEntry.next()) {
        consumer.accept(curEntry);
      }
    }
  }

  public static <C, TEntry extends Entry> void forEach(
      @Nonnull AtomicReferenceArray<TEntry> buckets,
      C context,
      @Nonnull BiConsumer<? super C, ? super TEntry> consumer) {
    for (int i = 0; i < buckets.length(); i++) {
      for (TEntry curEntry = buckets.get(i); curEntry != null; curEntry = curEntry.next()) {
        consumer.accept(context, curEntry);
      }
    }
  }

  /** {@link #forEach(AtomicReferenceArray, Consumer)} over a {@link State}. */
  public static <TEntry extends Entry> void forEach(
      @Nonnull State<TEntry> state, @Nonnull Consumer<? super TEntry> consumer) {
    forEach(state.buckets, consumer);
  }

  /** {@link #forEach(AtomicReferenceArray, Object, BiConsumer)} over a {@link State}. */
  public static <C, TEntry extends Entry> void forEach(
      @Nonnull State<TEntry> state,
      C context,
      @Nonnull BiConsumer<? super C, ? super TEntry> consumer) {
    forEach(state.buckets, context, consumer);
  }
}
