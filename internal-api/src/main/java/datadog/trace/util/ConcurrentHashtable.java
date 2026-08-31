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
 * use cases is that {@link D2#get(Object, Object)} and {@link D2#tryGetOrCreate(Object, Object,
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
 * #createFixedBuckets(Class, int)}, then operate on it with {@link #bucketFor} / {@link #bucketAt},
 * {@link #unlink}, {@link #removeIf}, {@link #drain}, {@link #clear}, and {@link #forEach}. This is
 * the same "static functions over a caller-owned array" shape as {@link Hashtable} (see how {@code
 * AggregateTable} uses {@code Hashtable}); the calling class then owns the array and exposes
 * whatever operations it needs. Subclass {@link Entry} directly for such tables.
 *
 * <p><b>Locking model.</b> Writes are guarded by monitors obtained from this class, never by
 * locking on an object the caller picked. Ask for the lock that covers the <em>scope</em> you are
 * about to mutate: {@link #getWriteLock(AtomicReferenceArray, long)} (or {@link
 * #getWriteLockAt(AtomicReferenceArray, int)}) for one key's bucket, and {@link
 * #getTableWriteLock(AtomicReferenceArray)} for anything spanning every bucket. Treat what comes
 * back as opaque rather than assuming it is the array. Reads are lock-free: {@link #bucketFor} /
 * {@link #bucketAt} walks and {@link #forEach} take no lock and are safe from any thread. The
 * whole-table mutators — {@link #removeIf}, {@link #drain}, {@link #clear} — are
 * <b>self-locking</b>, so a custom table calls them directly with no lock of its own. The only
 * writes a custom table performs by hand are single-key insert and remove; each is an atomic
 * check-then-write that the caller wraps in {@code synchronized (getWriteLock(buckets, keyHash))}
 * so it excludes other writers and the self-locking mutators (they nest cleanly with it):
 *
 * <ol>
 *   <li>Lock-free pre-check: walk the chain via {@link #bucketFor} / {@link #bucketAt}; return if
 *       found.
 *   <li>{@code synchronized (getWriteLock(buckets, keyHash))} — take the monitor covering that key.
 *   <li>Re-check under the lock (another thread may have inserted between step 1 and step 2).
 *   <li>Insert: build the entry and publish it with {@link #insertHeadEntryFor} / {@link
 *       #insertHeadEntryAt}. Remove: splice it out with {@link #unlink}. Both are volatile writes
 *       that lock-free readers observe atomically.
 * </ol>
 *
 * <p>{@link #bucketFor} / {@link #bucketAt} (a lock-free read), {@link #insertHeadEntryFor} /
 * {@link #insertHeadEntryAt}, and {@link #unlink} are the single-slot primitives for that
 * hand-written path; the two mutating ones do <b>not</b> lock, so call them only inside the
 * caller's {@code synchronized (getWriteLock(buckets, keyHash))} block. The entry's chain pointer
 * is written for you by those helpers — custom tables never touch it directly.
 *
 * <p><b>A sequence of self-locking calls is not atomic.</b> Each self-locking helper takes and
 * releases the monitor on its own, so two of them in a row leave a window in between. That matters
 * for any multi-step protocol over one table — notably reserving a slot with {@link
 * #tryReserveOrEvict} and then filling it with {@link #insertReserved}: a {@link #drain} or {@link
 * #clear} landing in the gap resets the {@link SizeManager} while the reservation is outstanding,
 * and the later insert then links an entry the count no longer knows about, so a capped table
 * drifts silently past its cap. Hold one lock across the whole protocol — {@link
 * #getTableWriteLock(State)} here, because a reservation is table-wide; the monitor is reentrant,
 * so the self-locking calls nest inside it cleanly.
 *
 * <p><b>On striping.</b> Every accessor above returns the same monitor today: writes to the whole
 * table serialize. The three accessors exist so that granularity is a choice this class can revisit
 * without touching its callers — a striped implementation would make {@link
 * #getWriteLockAt(AtomicReferenceArray, int)} resolve to a per-stripe monitor and leave {@link
 * #getWriteLock(AtomicReferenceArray, long)} unchanged at every call site. Two things would still
 * have to be settled first, and every {@code getTableWriteLock} use marks one of them:
 *
 * <ul>
 *   <li><b>Capacity accounting is table-wide.</b> {@link SizeManager}'s cap check and increment are
 *       one check-then-act over a shared counter, so a striped table would need per-stripe sub-caps
 *       — which is a different guarantee than one exact table-wide cap, not just a different lock.
 *       That is why the capped paths in {@link D1} / {@link D2} take the table lock.
 *   <li><b>Eviction scans every bucket.</b> {@link SizeManager#evictOne} walks the whole table from
 *       a shared cursor, so it needs every stripe. Confining it to the target stripe would make it
 *       stripeable, at the cost of turning approximate table-wide round-robin into per-stripe
 *       round-robin. That choice also decides the cursor: per-stripe it stays a plain {@code int}
 *       guarded by its stripe, while a cursor still shared across stripes becomes a genuine race to
 *       either accept as a best-effort hint or make {@code volatile}.
 * </ul>
 *
 * <p>The motivation, when it comes, is not write throughput on a read-mostly structure: it is that
 * {@link D1#tryGetOrCreateOrNull} runs the caller's {@code creator} inside the lock, so a burst of
 * misses on <em>different</em> keys serializes.
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
     * Creates a single-key table capped at {@code maxCapacity} entries: a {@link State} whose
     * bucket array is sized with load-factor headroom over {@code maxCapacity} and whose {@link
     * SizeManager} enforces {@code maxCapacity} as the strict entry-count limit consulted by {@link
     * #tryGetOrCreate}. The {@code entryClass} pins the concrete entry type so the compiler infers
     * both {@code K} and {@code TEntry} at the call site — e.g. {@code
     * D1.createCapped(MyEntry.class, 64)}. Capacity is fixed; the table does not resize.
     */
    @Nonnull
    public static <K, TEntry extends D1.Entry<K>> D1<K, TEntry> createCapped(
        @Nonnull Class<TEntry> entryClass, int maxCapacity) {
      return new D1<>(State.createCapped(entryClass, maxCapacity));
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
        // Deliberately isFull() -> create -> increment, not a pre-reserved slot: creator runs
        // between the check and the link and may throw, so reserving up front could leak a slot.
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
     * Creates a composite-key table capped at {@code maxCapacity} entries: a {@link State} whose
     * bucket array is sized with load-factor headroom over {@code maxCapacity} and whose {@link
     * SizeManager} enforces {@code maxCapacity} as the strict entry-count limit consulted by {@link
     * #tryGetOrCreate}. The {@code entryClass} pins the concrete entry type so the compiler infers
     * {@code K1}, {@code K2}, and {@code TEntry} at the call site — e.g. {@code
     * D2.createCapped(MyEntry.class, 64)}. Capacity is fixed; the table does not resize.
     */
    @Nonnull
    public static <K1, K2, TEntry extends D2.Entry<K1, K2>> D2<K1, K2, TEntry> createCapped(
        @Nonnull Class<TEntry> entryClass, int maxCapacity) {
      return new D2<>(State.createCapped(entryClass, maxCapacity));
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
        // Deliberately isFull() -> create -> increment, not a pre-reserved slot: creator runs
        // between the check and the link and may throw, so reserving up front could leak a slot.
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
   * Concurrent counterpart to {@link Hashtable.SizeManager}: manages a table's occupancy against a
   * fixed cap in both directions — reserving a slot for an insert and evicting to make room — so a
   * caller never has to remember to decrement after unlinking, nor wire up a second object
   * alongside the count.
   *
   * <p>{@link D1} and {@link D2} each hold one (via {@link State}) for their strict entry-count
   * cap; composers driving an {@link AtomicReferenceArray} through the static building blocks can
   * pair one the same way instead of hand-rolling the increment/decrement/cap-check bookkeeping —
   * see {@link State#createCapped}.
   *
   * <p><b>Locking.</b> {@link #estimateSize()}, {@link #capacity()}, and {@link #isFull()} read
   * only the atomic counter and need no lock. Every other method walks or mutates the chains (or
   * the eviction cursor) and must be called under {@code synchronized (getTableWriteLock(buckets))}
   * — the table-wide monitor, not one key's, since the count and the cursor are shared and eviction
   * walks every bucket — so a scan never races a concurrent insert or remove. Unlike {@link
   * Hashtable.SizeManager}'s plain {@code int}, the live count here is an {@link AtomicInteger}:
   * {@link #estimateSize()} and {@link #isFull()} are read without the lock (e.g. from {@link
   * D1#size()}), which a plain field could not support safely.
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
    private int cursor;

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
     * Reserves a slot for a fresh insert: increments and returns {@code true}, or leaves the count
     * unchanged and returns {@code false} if already at capacity. Use this when the entry to link
     * is already fully built (nothing between the check and the increment can fail). When building
     * the entry is itself fallible, check {@link #isFull()} first, do the fallible work, then call
     * {@link #increment()} only once linking actually succeeds — see {@link
     * D1#tryGetOrCreateOrNull} for that ordering.
     */
    @GuardedBy("getTableWriteLock(buckets)")
    public boolean tryReserve() {
      if (isFull()) {
        return false;
      }
      size.incrementAndGet();
      return true;
    }

    /**
     * {@link #tryReserve()}, falling back to evicting one entry matching {@code evictable} when the
     * table is full. Returns {@code true} with a slot reserved, or {@code false} if the table was
     * full and nothing was evictable — in which case {@code buckets} is untouched and the caller
     * should drop the datum.
     *
     * <p>The write lock must be held across the insert that consumes the reservation, not merely
     * across this call: {@link #reset()} (via a table-level drain or clear) zeroes the count, and a
     * reservation taken before it is silently voided.
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

    /** Zeroes both the live count and the eviction scan position. */
    @GuardedBy("getTableWriteLock(buckets)")
    @SuppressFBWarnings(
        value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
        justification =
            "cursor is read and written only under synchronized (getTableWriteLock(buckets)); SpotBugs"
                + " cannot model that dynamic guard")
    public void reset() {
      size.set(0);
      cursor = 0;
    }

    /**
     * Scans {@code buckets} for the first entry matching {@code evictable}, starting where the last
     * eviction left off and wrapping around if needed. Unlinks and returns the evicted entry,
     * decrementing the count; returns {@code null} (count untouched) if nothing matched anywhere.
     *
     * <p>Resuming from the previous position amortizes a sustained eviction stream: no successful
     * eviction re-scans the hot prefix more than twice. A call that matches nothing has, by
     * definition, tested every live entry, so a table that is full and entirely hot pays a full
     * pass per attempt; the cursor still steps on so repeated refusals at least start from a
     * different bucket next time. Size the cap to the steady-state working set so this stays the
     * rare path, and keep {@code evictable} cheap — it is called once per live entry on every
     * refusal.
     */
    @GuardedBy("getTableWriteLock(buckets)")
    @Nullable
    public <TEntry extends Entry> TEntry evictOne(
        @Nonnull AtomicReferenceArray<TEntry> buckets,
        @Nonnull Predicate<? super TEntry> evictable) {
      TEntry evicted = evictOneInRange(buckets, evictable, cursor, buckets.length());
      if (evicted == null && cursor != 0) {
        evicted = evictOneInRange(buckets, evictable, 0, cursor);
      }
      if (evicted != null) {
        size.decrementAndGet();
        return evicted;
      }
      // Nothing matched anywhere; step the cursor on regardless so repeated refusals don't all
      // restart the (wasted) scan from the same bucket.
      cursor = bucketIndex(buckets, cursor + 1);
      return null;
    }

    @GuardedBy("getTableWriteLock(buckets)")
    @SuppressFBWarnings(
        value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
        justification =
            "cursor is read and written only under synchronized (getTableWriteLock(buckets)); SpotBugs"
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
            cursor = i;
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
            "cursor is read and written only under synchronized (getTableWriteLock(buckets)); SpotBugs"
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
      cursor = 0;
      return count;
    }
  }

  /**
   * The mutable state of a caller-driven table: a bucket array and the {@link SizeManager} sized
   * and capped to match it. Both halves are stateful and neither is much use without the other,
   * which is what the name is getting at — the spine holds the entries, the manager holds how many
   * there are and where the last eviction looked.
   *
   * <p><b>Hold this, rather than unpacking it.</b> Keeping one field instead of two is not just
   * tidier: an array and a manager stored separately can drift apart, which is the mistake this
   * type exists to prevent. {@link D1} and {@link D2} hold one internally; composers reach through
   * it — {@code state.buckets}, {@code state.sizeManager} — when calling the static building blocks
   * directly, or use the {@code State}-taking overloads on this class.
   *
   * <p>Same headroom idiom as {@link D1}/{@link D2}: {@code maxCapacity} is the strict cap on live
   * entries, and the backing array is sized with load-factor headroom over it.
   */
  public static final class State<TEntry extends Entry> {
    public final AtomicReferenceArray<TEntry> buckets;
    public final SizeManager sizeManager;

    private State(AtomicReferenceArray<TEntry> buckets, int maxCapacity) {
      this.buckets = buckets;
      this.sizeManager = new SizeManager(maxCapacity);
    }

    /**
     * Creates a {@link State}: a bucket array sized with load-factor headroom over {@code
     * maxCapacity} (via {@link #createFixedBuckets(Class, int)}), paired with a {@link SizeManager}
     * capped at the strict {@code maxCapacity}. {@code entryClass} is a type token only — see
     * {@link #createFixedBuckets(Class, int)} for why it's needed despite not being used to
     * allocate.
     */
    @Nonnull
    public static <TEntry extends Entry> State<TEntry> createCapped(
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
   * Reserves a slot in {@code state} for a fresh insert, evicting one entry matching {@code
   * evictable} if the table is full. {@code false} means full with nothing evictable — the caller
   * should drop the datum. Self-locking.
   *
   * <p><b>Pairing with an insert:</b> the reservation this takes is only meaningful until the next
   * {@link #drain} or {@link #clear}, either of which resets the {@link SizeManager}. Because this
   * call releases the monitor before returning, a caller that follows it with {@link
   * #insertReserved} must hold {@code synchronized (getTableWriteLock(state))} across <b>both</b>
   * calls — see {@link #insertReserved} for the shape.
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
  @Nonnull
  public static <TEntry extends Entry> AtomicReferenceArray<TEntry> createFixedBuckets(
      @Nonnull Class<TEntry> entryClass, int capacity) {
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
   * Returns the monitor that guards writes to the bucket {@code keyHash} maps to. A custom table
   * locks on this — {@code synchronized (getWriteLock(buckets, keyHash)) { … }} — around its
   * scan-then-insert/remove for that one key.
   *
   * <p>Treat the returned object as <b>opaque</b>. It happens to be the bucket array today, and
   * every key returns the same monitor, but obtain it here rather than assuming either, so callers
   * stay correct if the locking granularity ever changes. Ask for the lock covering the key you are
   * about to mutate, not "the table's lock": a caller that holds the monitor for key A and mutates
   * key B is correct today only by accident.
   *
   * @see #getWriteLockAt(AtomicReferenceArray, int) when the bucket index is already computed
   * @see #getTableWriteLock(AtomicReferenceArray) for operations that span every bucket
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
   * Returns the monitor that excludes writers across <b>every</b> bucket. Needed by anything whose
   * effect is not confined to one bucket: capacity accounting ({@link SizeManager}, whose count and
   * eviction cursor are table-wide), eviction (which scans every bucket), and the whole-table
   * mutators {@link #drain} / {@link #clear} / {@link #removeIf} / {@link #evictAll} (which take it
   * themselves).
   *
   * <p>Today this is the same monitor {@link #getWriteLock(AtomicReferenceArray, long)} returns, so
   * the blocks nest freely. It is nonetheless the accessor to name when the operation really does
   * span the table — see the class javadoc on striping for why the distinction is worth keeping.
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
   * Splices {@code entry} in as the new head of the chain at {@code index}, publishing it with a
   * volatile {@link AtomicReferenceArray#set} so lock-free readers observe the whole entry (its
   * {@code next} already points at the old head) atomically. Single-slot primitive: it does not
   * lock, so call it inside the caller's {@code synchronized (getWriteLockAt(buckets, index))}
   * block, after re-checking the chain for the key under that lock. Does not touch size accounting.
   *
   * <p>See {@link #bucketFor} for why this is a distinct name rather than an {@code int} overload
   * of {@link #insertHeadEntryFor}.
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
   * Splices {@code entry} in as the new head of its bucket <em>without</em> touching the count,
   * because the caller already holds a reservation for it -- from {@link #tryReserveOrEvict} or a
   * bare {@link SizeManager#tryReserve()}. Pairing those is the shape of a miss path that wants to
   * refuse before it builds anything:
   *
   * <pre>{@code
   * synchronized (getTableWriteLock(state)) {     // ONE critical section for both steps
   *   if (!tryReserveOrEvict(state, evictable)) {
   *     return null;                         // refused -- no entry was built
   *   }
   *   insertReserved(state, keyHash, buildEntry());
   * }
   * }</pre>
   *
   * <p>The enclosing block is required, not stylistic. {@link #tryReserveOrEvict} is self-locking
   * and releases the monitor before it returns, so without it a {@link #drain} or {@link #clear}
   * can land between the reservation and this insert, reset the {@link SizeManager}, and leave this
   * insert linking an entry that the count no longer accounts for -- an undercount that never
   * heals, and on a {@link State#createCapped} table a cap that is quietly exceeded from then on.
   * The monitor is reentrant, so wrapping the self-locking call costs nothing.
   *
   * <p>Because the entry is built inside that block, {@code buildEntry()} must not throw: a throw
   * after the reservation is taken leaks a slot for the life of the table. When the build is
   * fallible, use the {@link D1#tryGetOrCreateOrNull} shape instead, which checks capacity, builds,
   * links, and only then increments.
   *
   * <p>Distinct from {@link #insertHeadEntryFor(AtomicReferenceArray, long, Entry)}, which reserves
   * as it inserts; calling that one here would count the entry twice. {@link D1} and {@link D2} do
   * not use this: their {@code creator} is fallible, so they check/evict, build the entry, link it,
   * and only then call {@link SizeManager#increment} -- reserving up front could leak a slot if the
   * build throws (see {@link D1#tryGetOrCreateOrNull}). Use this only when the entry is already
   * fully built before the reservation is taken.
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
   * Removes every entry, passing each to {@code sink} as its bucket is cleared. Each bucket head is
   * nulled (a volatile write that publishes the removal) before its chain is fed to {@code sink},
   * so new readers see an empty bucket while the detached chain — whose {@code next} pointers stay
   * intact — is handed to the caller. Self-locking: synchronizes on {@code buckets} for the whole
   * pass. Does not touch size accounting, so a caller tracking size resets it inside its own {@code
   * synchronized (getWriteLock(buckets, keyHash))} block (which nests with this one).
   *
   * <p>{@code sink} must not throw: buckets are detached as the sweep proceeds, so a sink that
   * throws part-way leaves earlier buckets drained and later ones intact, and any caller-side size
   * reset never runs. The drain is not rolled back — a throwing sink is a caller error.
   */
  public static <TEntry extends Entry> void drain(
      @Nonnull AtomicReferenceArray<TEntry> buckets, @Nonnull Consumer<? super TEntry> sink) {
    synchronized (getTableWriteLock(buckets)) {
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
      @Nonnull AtomicReferenceArray<TEntry> buckets,
      C context,
      @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
    synchronized (getTableWriteLock(buckets)) {
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

  /**
   * {@link #drain(AtomicReferenceArray, Consumer)} plus the matching bookkeeping: empties {@code
   * state} into {@code sink} and resets its {@link SizeManager} to zero. Draining without resetting
   * leaves the cap permanently consumed, so the two belong in one call rather than as a pair the
   * caller has to remember.
   */
  public static <TEntry extends Entry> void drain(
      @Nonnull State<TEntry> state, @Nonnull Consumer<? super TEntry> sink) {
    synchronized (getTableWriteLock(state)) {
      drain(state.buckets, sink);
      state.sizeManager.reset();
    }
  }

  /** Context-passing form of {@link #drain(State, Consumer)}. */
  public static <C, TEntry extends Entry> void drain(
      @Nonnull State<TEntry> state,
      C context,
      @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
    synchronized (getTableWriteLock(state)) {
      drain(state.buckets, context, sink);
      state.sizeManager.reset();
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
   * {@link #clear(AtomicReferenceArray)} over a {@link State}: also resets its {@link SizeManager}.
   */
  public static void clear(@Nonnull State<?> state) {
    synchronized (getTableWriteLock(state)) {
      clear(state.buckets);
      state.sizeManager.reset();
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
