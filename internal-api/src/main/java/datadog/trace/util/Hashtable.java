package datadog.trace.util;

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Light weight simple Hashtable system that can be useful when HashMap would be unnecessarily
 * heavy.
 *
 * <ul>
 *   Use cases include...
 *   <li>primitive keys
 *   <li>primitive values
 *   <li>multi-part keys
 * </ul>
 *
 * Convenience classes are provided for lower key dimensions.
 *
 * <p>For higher key dimensions, client code must implement its own class, but can still use the
 * static building blocks on this class to ease the implementation complexity.
 *
 * <p>This outer class is a pure namespace -- it can't be instantiated. The actual table types are
 * {@link D1}, {@link D2}, and (for higher-arity callers) custom tables driven by the static
 * building blocks on this class (see {@link #create(Class, int)}, {@link
 * #bucketFor(Hashtable.Entry[], long)}, {@link #insertHeadEntryAt(Hashtable.Entry[], int,
 * Hashtable.Entry)}, and friends).
 */
public final class Hashtable {
  private Hashtable() {}

  /**
   * Internal base class for entries. Stores the precomputed 64-bit keyHash and the chain-next
   * pointer used to link colliding entries within a single bucket.
   *
   * <p>Subclasses add the actual key field(s) and a {@code matches(...)} method tailored to their
   * key arity. See {@link D1.Entry} and {@link D2.Entry}; for higher arities, client code can
   * subclass this directly and drive the table with the static building blocks on {@link
   * Hashtable}.
   */
  public abstract static class Entry {
    public final long keyHash;
    private Entry next = null;

    protected Entry(long keyHash) {
      this.keyHash = keyHash;
    }

    public final <TEntry extends Entry> void setNext(@Nullable TEntry next) {
      this.next = next;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public final <TEntry extends Entry> TEntry next() {
      return (TEntry) this.next;
    }
  }

  /**
   * Single-key open hash table with chaining.
   *
   * <p>The user supplies an {@link D1.Entry} subclass that carries the key and whatever value
   * fields they want to mutate in place, then instantiates this class over that entry type. The
   * main advantage over {@code HashMap<K, V>} is that mutating an existing entry's value fields
   * requires no allocation: call {@link #get} once and write directly to the returned entry's
   * fields. For counter-style workloads this can be several times faster than {@code HashMap<K,
   * Long>} and produces effectively zero GC pressure.
   *
   * <p>Capacity is fixed at construction. The table does not resize, so the caller is responsible
   * for choosing a capacity appropriate to the working set. Once {@link #size()} reaches that
   * capacity, {@link #insert} returns {@code false} and {@link #tryGetOrCreate} returns {@code
   * null} rather than adding more entries -- a lookup hit is still always returned even at
   * capacity, the cap only blocks new entries. Want your own eviction policy instead of a hard cap?
   * Drop down to the static building blocks and drive the bucket array yourself -- {@link
   * Hashtable#createCappedTable(int)} hands you a spine and a {@link SizeManager} already matched
   * to each other, and the manager evicts as well as counts. Actual bucket-array length is rounded
   * up to the next power of two.
   *
   * <p>Null keys are permitted; they collapse to a single bucket via the sentinel hash {@link
   * Long#MIN_VALUE} defined in {@link D1.Entry#hash}.
   *
   * <p><b>Not thread-safe.</b> Concurrent access (including mixing reads with writes) requires
   * external synchronization.
   *
   * @param <K> the key type
   * @param <TEntry> the user's {@link D1.Entry D1.Entry&lt;K&gt;} subclass
   */
  public static final class D1<K, TEntry extends D1.Entry<K>> {
    /**
     * Abstract base for {@link D1} entries. Subclass to add value fields you wish to mutate in
     * place after retrieving the entry via {@link D1#get}.
     *
     * <p>The key is captured at construction and stored alongside its precomputed 64-bit hash.
     * {@link #matches(Object)} uses {@link Objects#equals} by default; override if a different
     * equality semantics is needed (e.g. reference equality for interned keys).
     *
     * @param <K> the key type
     */
    public abstract static class Entry<K> extends Hashtable.Entry {
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
        return Objects.equals(this.key, key);
      }

      /**
       * Returns the 64-bit lookup hash for {@code key}. Null keys map to {@link Long#MIN_VALUE} so
       * that they don't collide with a real key that hashes to 0 (e.g. {@code
       * Integer.hashCode(0)}). The {@code Long.MIN_VALUE} sentinel is safe against any {@code
       * int}-valued {@code hashCode()} since those widen to a long in the range {@code
       * [Integer.MIN_VALUE, Integer.MAX_VALUE]}; real-key collisions in chains are resolved by
       * {@link #matches(Object)}.
       */
      public static long hash(@Nullable Object key) {
        return (key == null) ? Long.MIN_VALUE : key.hashCode();
      }
    }

    // Package-private so iterator tests in the same package can drive the Hashtable static
    // building blocks directly against the table's bucket array.
    final Hashtable.Entry[] buckets;
    private final SizeManager sizeManager;

    private D1(int maxCapacity) {
      // Bucket array gets load-factor headroom over the strict entry cap below, so chains stay
      // short even when the table is full; see Hashtable#capacityFor.
      this.buckets = Hashtable.create(capacityFor(maxCapacity));
      this.sizeManager = new SizeManager(maxCapacity);
    }

    /**
     * A <em>capped</em> single-key table: it holds at most {@code maxCapacity} live entries, after
     * which {@link #insert} returns {@code false} and {@link #tryGetOrCreate} returns {@code null}.
     * A lookup hit is still always returned at capacity -- the cap only blocks new entries.
     *
     * <p>"Capped" names the promise, not the mechanism: the bucket array is sized once from {@code
     * maxCapacity} via {@link Hashtable#capacityFor(int)} and never resized, but that is an
     * implementation detail. What the caller is choosing here is a bounded entry count and, with
     * it, a bounded footprint -- the posture an agent living in someone else's heap wants by
     * default. Callers that need overflow to be absorbed rather than refused should pair a {@link
     * SizeManager}'s eviction half over the static building blocks (see {@link
     * Hashtable#createCappedTable(int)}) rather than reaching for an uncapped table.
     *
     * <p><b>Pick {@code maxCapacity} in the right ballpark of what you actually expect to hold</b>
     * -- the bucket array is sized from it, so it is read as both the limit and a rough estimate.
     * Nothing assumes you will reach the cap, but a cap set as a paranoid safety valve far above
     * typical usage over-allocates the spine for a fill that never arrives. When the limit and the
     * expectation genuinely differ by a lot, size the two independently with the low-level API:
     * {@code Hashtable.create(capacityFor(expected))} paired with {@code new SizeManager(limit)}.
     *
     * <p>{@code entryClass} is a type token only -- it pins the concrete entry type so the compiler
     * infers both {@code K} and {@code TEntry} at the call site (e.g. {@code
     * D1.createCapped(MyEntry.class, 64)}), keeping the factory symmetric with the rest of the
     * collections family. Unlike {@link Hashtable#create(Class, int)} it is not reflectively
     * allocated: {@code buckets} stays a plain {@code Hashtable.Entry[]} internally, matching the
     * static building blocks ({@link Hashtable#bucketFor}, {@link Hashtable#insertHeadEntryFor},
     * etc.) that {@link #get}, {@link #insert}, and friends delegate to.
     */
    @Nonnull
    public static <K, TEntry extends D1.Entry<K>> D1<K, TEntry> createCapped(
        @Nonnull Class<TEntry> entryClass, int maxCapacity) {
      return new D1<>(maxCapacity);
    }

    public int size() {
      return this.sizeManager.size();
    }

    /** {@code true} once {@link #size()} has reached this table's fixed capacity. */
    public boolean isFull() {
      return this.sizeManager.isFull();
    }

    @Nullable
    public TEntry get(@Nullable K key) {
      long keyHash = D1.Entry.hash(key);
      for (TEntry curEntry = bucketFor(this.buckets, keyHash);
          curEntry != null;
          curEntry = curEntry.next()) {
        if (curEntry.keyHash == keyHash && curEntry.matches(key)) {
          return curEntry;
        }
      }
      return null;
    }

    @Nullable
    public TEntry remove(@Nullable K key) {
      // Walks the chain directly rather than delegating to Hashtable#removeMatching: a
      // `e -> e.matches(key)` predicate captures `key`, so it allocates a fresh Predicate on every
      // call. This class ships context-passing forEach/drain overloads precisely so callers can
      // avoid capturing lambdas -- the write paths follow the same discipline. Same loop shape as
      // tryInsertOrReplace below.
      long keyHash = D1.Entry.hash(key);
      for (MutatingBucketIterator<TEntry> iter = mutatingBucketIterator(this.buckets, keyHash);
          iter.hasNext(); ) {
        TEntry curEntry = iter.next();
        if (curEntry.matches(key)) {
          iter.remove();
          this.sizeManager.decrement();
          return curEntry;
        }
      }
      return null;
    }

    /**
     * Unconditionally adds {@code newEntry} ({@code true}), or {@code false} if the table is
     * already at capacity. Caller-responsible: {@code newEntry}'s key must be absent, else it lands
     * shadowed behind the existing entry.
     */
    public boolean insert(@Nonnull TEntry newEntry) {
      return insertHeadEntryFor(this.sizeManager, this.buckets, newEntry.keyHash, newEntry);
    }

    /**
     * Makes {@code newEntry} the entry for its key: replaces the existing entry for that key if one
     * is present, otherwise inserts it fresh. Returns {@code false} only when the key is absent
     * <em>and</em> the table is at capacity -- a replacement swaps one entry for another without
     * growing {@link #size()}, so it always succeeds, even on a full table.
     *
     * <p>Does not hand back the entry it displaced. Callers that need it can {@link #get} first;
     * that is rare enough (the same way {@code Map.put}'s return value is rarely read) not to be
     * worth the cost of the alternative, which was throwing {@link IllegalStateException} on
     * refusal because {@code null} was already spoken for by "inserted fresh". Refusal at a cap is
     * ordinary steady-state behaviour, not a programming error, and an exception would allocate a
     * throwable plus stack trace exactly when the table is under the most pressure.
     *
     * <p>Note this swaps the entry <em>object</em>. Where the goal is to change values on an entry
     * that may or may not exist yet, prefer looking it up once and mutating in place -- that is the
     * allocation-free path this class exists for.
     */
    public boolean tryInsertOrReplace(@Nonnull TEntry newEntry) {
      for (MutatingBucketIterator<TEntry> iter =
              mutatingBucketIterator(this.buckets, newEntry.keyHash);
          iter.hasNext(); ) {
        TEntry curEntry = iter.next();

        if (curEntry.matches(newEntry.key)) {
          iter.replace(newEntry);
          return true;
        }
      }

      return insertHeadEntryFor(this.sizeManager, this.buckets, newEntry.keyHash, newEntry);
    }

    /**
     * Returns the entry for {@code key}, building one via {@code creator} if absent -- or {@code
     * null} if the key is absent and the table is <b>at capacity</b>. This method can refuse:
     * despite the name it is not total, and a caller that dereferences the result without a null
     * check will NPE the first time the cap is reached. A lookup hit is always returned even at
     * capacity, so only the create half can fail. Check {@link #isFull()} beforehand if you want to
     * distinguish "refused" from "created" without inspecting the result.
     *
     * <p>Refusal is a designed steady state for a capped table, not an exceptional condition -- see
     * {@link #createCapped}. Decide deliberately what a refused create should do (drop the sample,
     * fall back, make room); silently ignoring the {@code null} turns the cap into data loss you
     * cannot see.
     *
     * <p>Computes the hash once and reuses it for both the lookup and (on miss) the insert --
     * avoids the double-hash that "{@code get}; if null then {@code insert}" would incur.
     *
     * <p>The {@code creator} is expected to build an entry whose {@code keyHash} equals {@link
     * Entry#hash(Object) D1.Entry.hash(key)} -- typically by passing {@code key} to a constructor
     * that calls {@code super(key)}. A mismatched hash will leave the new entry inserted at a
     * bucket that future {@link #get} calls won't probe.
     */
    @Nullable
    public TEntry tryGetOrCreate(
        @Nullable K key, @Nonnull Function<? super K, ? extends TEntry> creator) {
      long keyHash = D1.Entry.hash(key);
      for (TEntry curEntry = bucketFor(this.buckets, keyHash);
          curEntry != null;
          curEntry = curEntry.next()) {
        if (curEntry.keyHash == keyHash && curEntry.matches(key)) {
          return curEntry;
        }
      }
      // Deliberately isFull() -> create -> increment, rather than the one-call tracked
      // insertHeadEntryFor(sizeManager, ...) that insert/tryInsertOrReplace use: `creator` runs
      // between the check and the link and may throw, so a slot reserved up front could leak. See
      // SizeManager#tryReserve.
      if (this.sizeManager.isFull()) {
        return null;
      }
      TEntry newEntry = creator.apply(key);
      insertHeadEntryFor(this.buckets, newEntry.keyHash, newEntry);
      this.sizeManager.increment();
      return newEntry;
    }

    public void forEach(@Nonnull Consumer<? super TEntry> consumer) {
      Hashtable.forEach(this.buckets, consumer);
    }

    /**
     * Context-passing forEach. Useful for callers that want to avoid a capturing-lambda allocation
     * -- pass a non-capturing {@link BiConsumer} (typically a {@code static final}) plus whatever
     * side-band state it needs as {@code context}.
     */
    public <C> void forEach(C context, @Nonnull BiConsumer<? super C, ? super TEntry> consumer) {
      Hashtable.forEach(this.buckets, context, consumer);
    }

    public void clear() {
      Hashtable.clear(this.sizeManager, this.buckets);
    }

    /**
     * Removes every entry, passing each to {@code sink} as it is unlinked -- the read-and-reset
     * primitive for flush/publish workflows (drain the table into a telemetry batch, an event
     * emitter, etc.). Equivalent to {@link #forEach} then {@link #clear} in a single call.
     */
    public void drain(@Nonnull Consumer<? super TEntry> sink) {
      Hashtable.drain(this.buckets, sink);
      this.sizeManager.reset();
    }

    /**
     * Context-passing {@link #drain(Consumer)}. Pass a non-capturing {@link BiConsumer} (typically
     * a {@code static final}) plus the accumulator as {@code context} to avoid a capturing-lambda
     * allocation.
     */
    public <C> void drain(C context, @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
      Hashtable.drain(this.buckets, context, sink);
      this.sizeManager.reset();
    }
  }

  /**
   * Two-key (composite-key) hash table with chaining.
   *
   * <p>The user supplies a {@link D2.Entry} subclass carrying both key parts and any value fields.
   * Compared to {@code HashMap<Pair, V>} this avoids the per-lookup {@code Pair} (or record)
   * allocation: both key parts are passed directly through {@link #get}, {@link #remove}, {@link
   * #insert}, and {@link #tryInsertOrReplace}. Combined with in-place value mutation, this makes
   * {@code D2} substantially less GC-intensive than the equivalent {@code HashMap<Pair, Long>} for
   * counter-style workloads.
   *
   * <p>Capacity is fixed at construction; the table does not resize. Same strict-cap semantics as
   * {@link D1} once {@link #size()} reaches capacity.
   *
   * <p>Key parts are combined into a 64-bit hash via {@link LongHashingUtils}; see {@link
   * D2.Entry#hash(Object, Object)}.
   *
   * <p><b>Not thread-safe.</b>
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
     * <p>Both key parts are captured at construction and stored alongside their combined 64-bit
     * hash. {@link #matches(Object, Object)} uses {@link Objects#equals} pairwise on the two parts.
     *
     * @param <K1> first key type
     * @param <K2> second key type
     */
    public abstract static class Entry<K1, K2> extends Hashtable.Entry {
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
        return Objects.equals(this.key1, key1) && Objects.equals(this.key2, key2);
      }

      /**
       * Returns the 64-bit lookup hash combining both key parts via {@link
       * LongHashingUtils#hash(Object, Object)}. Null parts contribute {@code 0} (not a sentinel,
       * unlike {@link D1.Entry#hash(Object)}): the combined hash can collide with real-key
       * combinations whose chained hash equals {@code hash(0, 0) = 0} or similar values. {@link
       * #matches(Object, Object)} resolves any such collision.
       */
      public static long hash(@Nullable Object key1, @Nullable Object key2) {
        return LongHashingUtils.hash(key1, key2);
      }
    }

    // Package-private to match D1.buckets -- available for iterator tests in the same package.
    final Hashtable.Entry[] buckets;
    private final SizeManager sizeManager;

    private D2(int maxCapacity) {
      // Bucket array gets load-factor headroom over the strict entry cap below, so chains stay
      // short even when the table is full; see Hashtable#capacityFor.
      this.buckets = Hashtable.create(capacityFor(maxCapacity));
      this.sizeManager = new SizeManager(maxCapacity);
    }

    /**
     * Composite-key analogue of {@link D1#createCapped}: a <em>capped</em> table holding at most
     * {@code maxCapacity} live entries, after which {@link #insert} returns {@code false} and
     * {@link #tryGetOrCreate} returns {@code null}, with lookup hits still always returned. See
     * {@link D1#createCapped} for what "capped" promises and why it is the default posture.
     *
     * <p>{@code entryClass} is a type token only -- it pins the concrete entry type so the compiler
     * infers {@code K1}, {@code K2}, and {@code TEntry} at the call site (e.g. {@code
     * D2.createCapped(MyEntry.class, 64)}). Unlike {@link Hashtable#create(Class, int)} it is not
     * reflectively allocated: {@code buckets} stays a plain {@code Hashtable.Entry[]} internally,
     * matching the static building blocks that {@link #get}, {@link #insert}, and friends delegate
     * to.
     */
    @Nonnull
    public static <K1, K2, TEntry extends D2.Entry<K1, K2>> D2<K1, K2, TEntry> createCapped(
        @Nonnull Class<TEntry> entryClass, int maxCapacity) {
      return new D2<>(maxCapacity);
    }

    public int size() {
      return this.sizeManager.size();
    }

    /** {@code true} once {@link #size()} has reached this table's fixed capacity. */
    public boolean isFull() {
      return this.sizeManager.isFull();
    }

    @Nullable
    public TEntry get(@Nullable K1 key1, @Nullable K2 key2) {
      long keyHash = D2.Entry.hash(key1, key2);
      for (TEntry curEntry = bucketFor(this.buckets, keyHash);
          curEntry != null;
          curEntry = curEntry.next()) {
        if (curEntry.keyHash == keyHash && curEntry.matches(key1, key2)) {
          return curEntry;
        }
      }
      return null;
    }

    @Nullable
    public TEntry remove(@Nullable K1 key1, @Nullable K2 key2) {
      // Chain walked directly rather than via Hashtable#removeMatching -- see D1#remove for why a
      // capturing predicate is avoided on this path.
      long keyHash = D2.Entry.hash(key1, key2);
      for (MutatingBucketIterator<TEntry> iter = mutatingBucketIterator(this.buckets, keyHash);
          iter.hasNext(); ) {
        TEntry curEntry = iter.next();
        if (curEntry.matches(key1, key2)) {
          iter.remove();
          this.sizeManager.decrement();
          return curEntry;
        }
      }
      return null;
    }

    /** Two-key analogue of {@link D1#insert}, with the same strict-cap refusal contract. */
    public boolean insert(@Nonnull TEntry newEntry) {
      return insertHeadEntryFor(this.sizeManager, this.buckets, newEntry.keyHash, newEntry);
    }

    /** Two-key analogue of {@link D1#tryInsertOrReplace}, with the same refusal contract. */
    public boolean tryInsertOrReplace(@Nonnull TEntry newEntry) {
      for (MutatingBucketIterator<TEntry> iter =
              mutatingBucketIterator(this.buckets, newEntry.keyHash);
          iter.hasNext(); ) {
        TEntry curEntry = iter.next();

        if (curEntry.matches(newEntry.key1, newEntry.key2)) {
          iter.replace(newEntry);
          return true;
        }
      }

      return insertHeadEntryFor(this.sizeManager, this.buckets, newEntry.keyHash, newEntry);
    }

    /**
     * Two-key analogue of {@link D1#tryGetOrCreate}: returns the entry for {@code (key1, key2)},
     * building one via {@code creator} if absent -- or {@code null} if the pair is absent and the
     * table is <b>at capacity</b>. Like the single-key form it is not total despite the name, and
     * refusal is a designed steady state rather than an exceptional one; see {@link
     * D1#tryGetOrCreate} for the full contract and what to do about a refused create.
     *
     * <p>Computes the combined hash once and reuses it for both lookup and (on miss) insert. The
     * {@code creator} is expected to build an entry whose {@code keyHash} equals {@link
     * Entry#hash(Object, Object) D2.Entry.hash(key1, key2)}.
     */
    @Nullable
    public TEntry tryGetOrCreate(
        @Nullable K1 key1,
        @Nullable K2 key2,
        @Nonnull BiFunction<? super K1, ? super K2, ? extends TEntry> creator) {
      long keyHash = D2.Entry.hash(key1, key2);
      for (TEntry curEntry = bucketFor(this.buckets, keyHash);
          curEntry != null;
          curEntry = curEntry.next()) {
        if (curEntry.keyHash == keyHash && curEntry.matches(key1, key2)) {
          return curEntry;
        }
      }
      // Deliberately isFull() -> create -> increment, rather than the one-call tracked
      // insertHeadEntryFor(sizeManager, ...) that insert/tryInsertOrReplace use: `creator` runs
      // between the check and the link and may throw, so a slot reserved up front could leak. See
      // SizeManager#tryReserve.
      if (this.sizeManager.isFull()) {
        return null;
      }
      TEntry newEntry = creator.apply(key1, key2);
      insertHeadEntryFor(this.buckets, newEntry.keyHash, newEntry);
      this.sizeManager.increment();
      return newEntry;
    }

    public void forEach(@Nonnull Consumer<? super TEntry> consumer) {
      Hashtable.forEach(this.buckets, consumer);
    }

    /**
     * Context-passing forEach. Useful for callers that want to avoid a capturing-lambda allocation
     * -- pass a non-capturing {@link BiConsumer} (typically a {@code static final}) plus whatever
     * side-band state it needs as {@code context}.
     */
    public <C> void forEach(C context, @Nonnull BiConsumer<? super C, ? super TEntry> consumer) {
      Hashtable.forEach(this.buckets, context, consumer);
    }

    public void clear() {
      Hashtable.clear(this.sizeManager, this.buckets);
    }

    /**
     * Removes every entry, passing each to {@code sink} as it is unlinked -- the read-and-reset
     * primitive for flush/publish workflows (drain the table into a telemetry batch, an event
     * emitter, etc.). Equivalent to {@link #forEach} then {@link #clear} in a single call.
     */
    public void drain(@Nonnull Consumer<? super TEntry> sink) {
      Hashtable.drain(this.buckets, sink);
      this.sizeManager.reset();
    }

    /**
     * Context-passing {@link #drain(Consumer)}. Pass a non-capturing {@link BiConsumer} (typically
     * a {@code static final}) plus the accumulator as {@code context} to avoid a capturing-lambda
     * allocation.
     */
    public <C> void drain(C context, @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
      Hashtable.drain(this.buckets, context, sink);
      this.sizeManager.reset();
    }
  }

  // ============================================================================================
  // Static building blocks over a caller-owned bucket array.
  //
  // Use these to assemble a custom table (higher arity, primitive keys, extra value fields) when
  // D1/D2 don't fit; D1/D2 delegate to them internally. The calling class owns the array and
  // exposes whatever operations it needs.
  //
  // Not thread-safe: there is no locking here. Concurrent access, including mixing reads with
  // writes, requires external synchronization.
  // ============================================================================================

  /** Upper bound on the bucket-array length returned by {@link #sizeFor(int)}. */
  static final int MAX_BUCKETS = 1 << 30;

  /**
   * Allocates a fixed-size bucket array sized to hold {@code capacity} entries: {@code capacity}
   * rounded up to the next power of two.
   *
   * <p>Erasure stops a caller writing {@code new TEntry[n]}, so {@code entryClass} is allocated
   * reflectively via {@link Array#newInstance}. That buys a real {@code TEntry} component type
   * rather than the base {@code Entry[]}: typed reads, real array-store checks, and a monomorphic
   * element type for the JIT. The one reflective call happens at construction, off any hot path.
   * Capacity is fixed; the table does not resize. Use {@link #create(int)} when the spine is driven
   * purely through the static building blocks and the base component type is enough.
   *
   * <p>{@code capacity} sizes the bucket array 1:1 (no headroom) -- chains stay a plain hash table
   * at exactly this many entries. For load-factor headroom over a target cap on live entries (so
   * chains stay short even as the table fills, the way {@link D1}/{@link D2}/{@link
   * #createCappedTable} size themselves), pass {@link #capacityFor(int)} instead: {@code
   * create(MyEntry.class, capacityFor(cardinalityLimit))}.
   */
  @SuppressWarnings("unchecked")
  @Nonnull
  public static <TEntry extends Entry> TEntry[] create(
      @Nonnull Class<TEntry> entryClass, int capacity) {
    return (TEntry[]) Array.newInstance(entryClass, sizeFor(capacity));
  }

  /**
   * Untyped sibling of {@link #create(Class, int)}: allocates a bucket array of {@code buckets}
   * rounded up to the next power of two, with the base {@code Hashtable.Entry[]} component type.
   *
   * <p>Use this when the spine is driven purely through the static building blocks, which all take
   * {@code Hashtable.Entry[]} -- that is what {@link D1}, {@link D2}, and {@link
   * #createCappedTable} allocate internally. Prefer {@link #create(Class, int)} when you own the
   * array and want a real {@code TEntry} component type (typed reads, array-store checks, a
   * monomorphic element type for the JIT); prefer this one when a typed spine would only buy you
   * covariant array-store checks on every insert. Capacity is fixed; the table does not resize.
   *
   * <p>{@code buckets} is a bucket count, not an entry cap -- see {@link #capacityFor(int)} to
   * derive one from a target cap on live entries.
   */
  @Nonnull
  public static Hashtable.Entry[] create(int buckets) {
    return new Hashtable.Entry[sizeFor(buckets)];
  }

  /**
   * Balanced default load factor for a chained bucket array: at this target fill, chains from a
   * well-spread hash stay short (average chain length {@code ~1/DEFAULT_LOAD_FACTOR}) without
   * over-provisioning the array. Chaining tolerates a high target fill: past 1.0 it degrades
   * gradually into longer chains rather than failing, so there is no cliff to stay clear of and no
   * reason to over-allocate the spine.
   */
  public static final float DEFAULT_LOAD_FACTOR = 0.75f;

  /**
   * Bucket-array length for a strict cap of {@code cardinalityLimit} live entries at {@link
   * #DEFAULT_LOAD_FACTOR}: infers a reasonable bucket count from the entry cap you actually care
   * about, rather than making every caller redo the headroom math ({@link D1}, {@link D2}, and
   * {@link #createCappedTable} all size themselves this way). Pair with a {@link SizeManager} of
   * {@code cardinalityLimit} for the matching strict cap; this method only sizes the array.
   */
  public static int capacityFor(int cardinalityLimit) {
    return capacityFor(cardinalityLimit, DEFAULT_LOAD_FACTOR);
  }

  /**
   * {@link #capacityFor(int)} at an explicit {@code loadFactor} in {@code (0, 1)}: the bucket-array
   * length for a strict cap of {@code cardinalityLimit} live entries, rounded up to a power of two
   * via {@link #sizeFor(int)}.
   */
  public static int capacityFor(int cardinalityLimit, float loadFactor) {
    if (!(loadFactor > 0f && loadFactor < 1f)) {
      throw new IllegalArgumentException("loadFactor must be in (0, 1): " + loadFactor);
    }
    return sizeFor((int) (cardinalityLimit / loadFactor));
  }

  /**
   * Rounds {@code requestedSize} up to the next power of two, capped at {@link #MAX_BUCKETS}, and
   * returns the bucket-array length to allocate. Throws {@link IllegalArgumentException} for
   * negative inputs or inputs above the cap.
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

  public static int bucketIndex(@Nonnull Object[] buckets, long keyHash) {
    return (int) (keyHash & buckets.length - 1);
  }

  /**
   * Returns the head entry of the bucket that {@code keyHash} maps to, cast to the caller's
   * concrete entry type. The unchecked cast lives here so the chain-walk loop at the call site
   * doesn't need to thread a raw {@link Entry} variable through.
   *
   * <p>Named {@code bucketFor} rather than {@code bucket}: there is no competing {@code int}-index
   * overload today, but the {@code For} suffix marks "derives the index from a key hash" up front,
   * so adding an index-taking sibling later cannot reintroduce the int-vs-long overload ambiguity
   * described on {@link #insertHeadEntryFor(Hashtable.Entry[], long, Hashtable.Entry)}.
   */
  @SuppressWarnings("unchecked")
  @Nullable
  public static <TEntry extends Entry> TEntry bucketFor(
      @Nonnull Hashtable.Entry[] buckets, long keyHash) {
    return (TEntry) buckets[bucketIndex(buckets, keyHash)];
  }

  /**
   * Splices {@code entry} in as the new head of the chain at {@code bucketIndex}. Caller is
   * responsible for size accounting -- this method only touches the chain pointers.
   */
  public static void insertHeadEntryAt(
      @Nonnull Hashtable.Entry[] buckets, int bucketIndex, @Nonnull Hashtable.Entry entry) {
    entry.setNext(buckets[bucketIndex]);
    buckets[bucketIndex] = entry;
  }

  /**
   * Convenience form of {@link #insertHeadEntryAt} that derives the bucket index from {@code
   * keyHash}. Use this when the caller has the hash but not the index; if the index has already
   * been computed for another reason, prefer {@link #insertHeadEntryAt} to avoid the redundant
   * mask.
   *
   * <p>Named distinctly from {@link #insertHeadEntryAt} rather than overloaded on {@code long} vs.
   * {@code int}, because the overloaded form is a trap: a caller with a primitive {@code int}-typed
   * key hash calling an overloaded {@code insertHeadEntry(buckets, intHash, entry)} would silently
   * bind to the {@code int}-index overload instead of widening to this one, treating the raw hash
   * as an array index.
   */
  public static void insertHeadEntryFor(
      @Nonnull Hashtable.Entry[] buckets, long keyHash, @Nonnull Hashtable.Entry entry) {
    insertHeadEntryAt(buckets, bucketIndex(buckets, keyHash), entry);
  }

  /**
   * {@link #insertHeadEntryFor(Hashtable.Entry[], long, Hashtable.Entry)}, but folding in the
   * strict-cap check that every unconditional insert needs: reserves a slot from {@code
   * sizeManager} first, splicing {@code entry} in only if the reservation succeeds. Returns {@code
   * false} (without touching {@code buckets}) once {@code sizeManager} is at capacity. Lets a
   * composer working directly against the static building blocks (e.g. {@link D1#insert}, or a
   * caller-owned table of higher key arity) get the same one-call insert-with-cap-check contract
   * that {@link D1}/{@link D2} give their own callers.
   *
   * <p>{@code sizeManager} leads, per this class's parameter order for the size-tracked statics:
   * mutated bookkeeping, then the spine, then the key, then callbacks. Putting it first (rather
   * than appending it) makes the tracked and untracked forms visibly different at the head of the
   * call instead of differing only in a trailing argument -- forgetting the tracker leaks the cap
   * silently, so the distinction should be hard to overlook at the call site and in review.
   */
  public static boolean insertHeadEntryFor(
      @Nonnull SizeManager sizeManager,
      @Nonnull Hashtable.Entry[] buckets,
      long keyHash,
      @Nonnull Hashtable.Entry entry) {
    if (!sizeManager.tryReserve()) {
      return false;
    }
    insertHeadEntryFor(buckets, keyHash, entry);
    return true;
  }

  /**
   * Scans the bucket chain at {@code keyHash} for the first entry matching {@code matches}, unlinks
   * it, decrements {@code sizeManager}, and returns it -- or returns {@code null} (leaving {@code
   * buckets} and {@code sizeManager} untouched) if nothing in the chain matches. Mirrors {@link
   * #insertHeadEntryFor(SizeManager, Hashtable.Entry[], long, Hashtable.Entry)} on the removal
   * side: the one-call, size-tracked shape a composer driving the static building blocks directly
   * can use instead of hand-rolling the mutating-iterator loop and remembering to decrement.
   *
   * <p>{@code sizeManager} leads for the same reason it does on the insert side.
   */
  @Nullable
  public static <TEntry extends Entry> TEntry removeMatching(
      @Nonnull SizeManager sizeManager,
      @Nonnull Hashtable.Entry[] buckets,
      long keyHash,
      @Nonnull Predicate<? super TEntry> matches) {
    for (MutatingBucketIterator<TEntry> iter = mutatingBucketIterator(buckets, keyHash);
        iter.hasNext(); ) {
      TEntry curEntry = iter.next();
      if (matches.test(curEntry)) {
        iter.remove();
        sizeManager.decrement();
        return curEntry;
      }
    }
    return null;
  }

  /**
   * Walks every entry in {@code buckets} and invokes {@code consumer} on it. The unchecked cast to
   * {@code TEntry} lives here (mirroring {@link Entry#next()}) so callers don't have to sprinkle it
   * across their own forEach loops.
   */
  @SuppressWarnings("unchecked")
  public static <TEntry extends Entry> void forEach(
      @Nonnull Hashtable.Entry[] buckets, @Nonnull Consumer<? super TEntry> consumer) {
    for (int i = 0; i < buckets.length; i++) {
      for (Hashtable.Entry e = buckets[i]; e != null; e = e.next()) {
        consumer.accept((TEntry) e);
      }
    }
  }

  /**
   * Context-passing variant of {@link #forEach(Hashtable.Entry[], Consumer)}. Pair a non-capturing
   * {@link BiConsumer} (typically a {@code static final}) with side-band state passed as {@code
   * context} to avoid a fresh-Consumer allocation each call.
   */
  @SuppressWarnings("unchecked")
  public static <C, TEntry extends Entry> void forEach(
      @Nonnull Hashtable.Entry[] buckets,
      C context,
      @Nonnull BiConsumer<? super C, ? super TEntry> consumer) {
    for (int i = 0; i < buckets.length; i++) {
      for (Hashtable.Entry e = buckets[i]; e != null; e = e.next()) {
        consumer.accept(context, (TEntry) e);
      }
    }
  }

  @Nonnull
  public static <TEntry extends Hashtable.Entry> BucketIterator<TEntry> bucketIterator(
      @Nonnull Hashtable.Entry[] buckets, long keyHash) {
    return new BucketIterator<TEntry>(buckets, keyHash);
  }

  @Nonnull
  public static <TEntry extends Hashtable.Entry>
      MutatingBucketIterator<TEntry> mutatingBucketIterator(
          @Nonnull Hashtable.Entry[] buckets, long keyHash) {
    return new MutatingBucketIterator<TEntry>(buckets, keyHash);
  }

  /**
   * Returns a {@link MutatingTableIterator} over every entry in {@code buckets}. Useful for sweeps
   * -- eviction, expunge -- that aren't keyed to a specific hash.
   */
  @Nonnull
  public static <TEntry extends Hashtable.Entry>
      MutatingTableIterator<TEntry> mutatingTableIterator(@Nonnull Hashtable.Entry[] buckets) {
    return new MutatingTableIterator<TEntry>(buckets, 0, buckets.length);
  }

  /**
   * Variant of {@link #mutatingTableIterator(Hashtable.Entry[])} that walks only the half-open
   * bucket range {@code [startBucket, endBucket)}. Useful for resumable sweeps -- e.g. the
   * cursor-based eviction in {@link SizeManager#evictOne} -- where one call drives {@code [cursor,
   * length)} and a wrap-around call drives {@code [0, cursor)}. The iterator does <b>not</b> wrap
   * around within a single instance; callers compose two iterators when wrap-around is desired. An
   * empty range ({@code startBucket == endBucket}) produces an immediately exhausted iterator.
   *
   * @param startBucket inclusive lower bound; must be in {@code [0, buckets.length]}.
   * @param endBucket exclusive upper bound; must be in {@code [startBucket, buckets.length]}.
   */
  @Nonnull
  public static <TEntry extends Hashtable.Entry>
      MutatingTableIterator<TEntry> mutatingTableIterator(
          @Nonnull Hashtable.Entry[] buckets, int startBucket, int endBucket) {
    return new MutatingTableIterator<TEntry>(buckets, startBucket, endBucket);
  }

  public static void clear(@Nonnull Hashtable.Entry[] buckets) {
    Arrays.fill(buckets, null);
  }

  /**
   * {@link #clear(Hashtable.Entry[])} plus the matching bookkeeping: empties {@code buckets} and
   * resets {@code sizeManager} to zero. Emptying a table without resetting its tracker leaves the
   * cap permanently consumed, so the two belong in one call rather than as a pair a caller has to
   * remember.
   *
   * <p>{@code sizeManager} leads, per this class's parameter order for the size-tracked statics.
   */
  public static void clear(@Nonnull SizeManager sizeManager, @Nonnull Hashtable.Entry[] buckets) {
    clear(buckets);
    sizeManager.reset();
  }

  /**
   * Removes every entry, passing each removed entry to {@code sink} as it is unlinked -- the
   * read-and-reset primitive for flush/publish workflows (drain the table into a telemetry batch,
   * an event emitter, etc.). Equivalent to {@link #forEach} then {@link #clear}, offered as one
   * call so composers don't have to spell out both steps.
   */
  public static <TEntry extends Entry> void drain(
      @Nonnull Hashtable.Entry[] buckets, @Nonnull Consumer<? super TEntry> sink) {
    Hashtable.<TEntry>forEach(buckets, sink);
    clear(buckets);
  }

  /**
   * Context-passing variant of {@link #drain(Hashtable.Entry[], Consumer)}. Pass a non-capturing
   * {@link BiConsumer} (typically a {@code static final}) plus the accumulator as {@code context}
   * (e.g. the target list or event builder) to avoid a capturing-lambda allocation.
   */
  public static <C, TEntry extends Entry> void drain(
      @Nonnull Hashtable.Entry[] buckets,
      C context,
      @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
    Hashtable.<C, TEntry>forEach(buckets, context, sink);
    clear(buckets);
  }

  /**
   * Manages a table's occupancy against a fixed cap -- both directions. Reserving a slot for an
   * insert and evicting to make room are two halves of the same policy, so they live on one object:
   * a caller never has to remember to decrement after unlinking, and there is no second object to
   * wire up (or mis-wire) alongside the count.
   *
   * <p>{@link D1} and {@link D2} use one internally for their strict entry-count cap; composers
   * driving a {@code Hashtable.Entry[]} through the static building blocks can reuse it instead of
   * hand-rolling the same increment/decrement/cap-check bookkeeping. A table that never evicts
   * simply never calls the eviction half.
   *
   * <pre>{@code
   * // miss path of a capped, self-evicting table
   * if (!sizeManager.tryReserveOrEvict(buckets, STALE)) {
   *   return null;                       // full, and nothing was evictable -- drop the datum
   * }
   * insertHeadEntryFor(buckets, keyHash, newEntry);   // slot already reserved
   * }</pre>
   *
   * <p>Not thread-safe, matching the rest of this class.
   */
  public static final class SizeManager {
    private final int capacity;
    private int size;

    /**
     * Bucket index the last eviction removed from. The next scan resumes here, so a sustained
     * eviction stream doesn't repeatedly re-walk the same hot entries clustered near bucket 0.
     */
    private int cursor;

    public SizeManager(int capacity) {
      this.capacity = capacity;
    }

    public int size() {
      return this.size;
    }

    public int capacity() {
      return this.capacity;
    }

    /** {@code true} once {@link #size()} has reached {@link #capacity()}. */
    public boolean isFull() {
      return this.size >= this.capacity;
    }

    /**
     * Reserves a slot for a fresh insert: increments and returns {@code true}, or leaves the count
     * unchanged and returns {@code false} if already at capacity. Use this when the entry to link
     * is already fully built (nothing between the check and the increment can fail). When building
     * the entry is itself fallible, check {@link #isFull()} first, do the fallible work, then call
     * {@link #increment()} only once linking actually succeeds.
     *
     * <p>Returning {@code false} is not a final refusal -- it is the caller's cue to either refuse
     * the insert or make room. {@link #tryReserveOrEvict} folds those two steps into one call.
     */
    public boolean tryReserve() {
      if (isFull()) {
        return false;
      }
      this.size += 1;
      return true;
    }

    /**
     * {@link #tryReserve()}, falling back to evicting one entry matching {@code evictable} when the
     * table is full. Returns {@code true} with a slot reserved, or {@code false} if the table was
     * full and nothing was evictable -- in which case {@code buckets} is untouched and the caller
     * should drop the datum.
     *
     * <p>The whole capacity decision of a self-evicting table's miss path, in one call. Pass a
     * non-capturing {@code evictable} (typically a {@code static final}) to keep it
     * allocation-free.
     */
    public boolean tryReserveOrEvict(
        @Nonnull Hashtable.Entry[] buckets, @Nonnull Predicate<? super Entry> evictable) {
      if (tryReserve()) {
        return true;
      }
      if (evictOne(buckets, evictable) == null) {
        return false;
      }
      // evictOne decremented; the slot it freed is ours.
      this.size += 1;
      return true;
    }

    /** Call after successfully linking a new entry. */
    public void increment() {
      this.size += 1;
    }

    /** Call after successfully unlinking an entry. */
    public void decrement() {
      this.size -= 1;
    }

    /** Zeroes both the live count and the eviction scan position. */
    public void reset() {
      this.size = 0;
      this.cursor = 0;
    }

    /**
     * Scans {@code buckets} for the first entry matching {@code evictable}, starting where the last
     * eviction left off and wrapping around if needed. Unlinks and returns the evicted entry,
     * decrementing the count; returns {@code null} (count untouched) if nothing matched anywhere.
     *
     * <p>Resuming from the previous position is what keeps a sustained eviction stream amortized:
     * the worst case for a single call is still O(N) when nearly every entry is hot, but N
     * evictions never re-scan the hot prefix more than twice.
     */
    @Nullable
    public Entry evictOne(
        @Nonnull Hashtable.Entry[] buckets, @Nonnull Predicate<? super Entry> evictable) {
      Entry evicted = evictOneInRange(buckets, evictable, this.cursor, buckets.length);
      if (evicted == null && this.cursor != 0) {
        evicted = evictOneInRange(buckets, evictable, 0, this.cursor);
      }
      if (evicted != null) {
        this.size -= 1;
      }
      return evicted;
    }

    @Nullable
    private Entry evictOneInRange(
        @Nonnull Hashtable.Entry[] buckets,
        @Nonnull Predicate<? super Entry> evictable,
        int startBucket,
        int endBucket) {
      MutatingTableIterator<Entry> iter = mutatingTableIterator(buckets, startBucket, endBucket);
      while (iter.hasNext()) {
        Entry candidate = iter.next();
        if (evictable.test(candidate)) {
          int bucket = iter.currentBucket();
          iter.remove();
          this.cursor = bucket;
          return candidate;
        }
      }
      return null;
    }

    /**
     * Unlinks every entry matching {@code evictable} in one full pass, decrementing the count for
     * each, and returns how many were removed. Resets the scan position, since a full pass leaves
     * nothing later to resume from.
     *
     * <p>Named {@code evictAll} rather than {@code drain} to keep it distinct from {@link
     * Hashtable#drain(Hashtable.Entry[], Consumer)}, which empties the whole table into a sink.
     * This one removes only what matches, and hands back a count rather than the entries.
     */
    public int evictAll(
        @Nonnull Hashtable.Entry[] buckets, @Nonnull Predicate<? super Entry> evictable) {
      int count = 0;
      MutatingTableIterator<Entry> iter = mutatingTableIterator(buckets);
      while (iter.hasNext()) {
        Entry candidate = iter.next();
        if (evictable.test(candidate)) {
          iter.remove();
          count++;
        }
      }
      this.size -= count;
      this.cursor = 0;
      return count;
    }
  }

  /**
   * Bundles a bucket array together with a {@link SizeManager} sized and matched to it, so a
   * composer driving the static building blocks directly gets everything it needs to store from one
   * factory call, instead of separately sizing an array and a manager that must stay in sync with
   * it. Same headroom idiom as {@link D1}/{@link D2}'s constructors: {@code capacity} is the strict
   * cap on live entries, and the backing array is sized with load-factor headroom over it.
   *
   * <p>Store the pieces of this bundle into your own fields; nothing here is meant to be held onto
   * as a {@code Table} itself.
   */
  public static final class Table {
    public final Hashtable.Entry[] buckets;
    public final SizeManager sizeManager;

    private Table(Hashtable.Entry[] buckets, int maxCapacity) {
      this.buckets = buckets;
      this.sizeManager = new SizeManager(maxCapacity);
    }
  }

  /**
   * Creates a {@link Table}: a bucket array sized with load-factor headroom over {@code
   * maxCapacity}, paired with a {@link SizeManager} capped at the strict {@code maxCapacity}.
   */
  @Nonnull
  public static Table createCappedTable(int maxCapacity) {
    Hashtable.Entry[] buckets = create(capacityFor(maxCapacity));
    return new Table(buckets, maxCapacity);
  }

  /**
   * Deprecated facade over the static building blocks that are now methods on {@link Hashtable}
   * itself. Every member here delegates to its {@code Hashtable.*} counterpart -- no real logic
   * lives in this class, so it can be deleted outright once the last caller migrates.
   *
   * <p>Retained only for source compatibility with existing callers. New code should call the
   * {@code Hashtable.*} statics directly.
   *
   * @deprecated use the static building blocks on {@link Hashtable} directly.
   */
  @Deprecated
  public static final class Support {
    private Support() {}

    /**
     * @deprecated use {@link Hashtable#create(int)} (or {@link Hashtable#create(Class, int)} for a
     *     typed spine).
     */
    @Deprecated
    @Nonnull
    public static Hashtable.Entry[] create(int requestedSize) {
      return Hashtable.create(requestedSize);
    }

    /**
     * Scales the requested working-set size before sizing the bucket array. Pair with {@link
     * #MAX_RATIO} to leave headroom over the working set for a desired load factor; the canonical
     * call is {@code create(n, MAX_RATIO)}.
     *
     * <p>The scaled size is truncated to {@code int} before going through {@link
     * Hashtable#sizeFor(int)}. Truncation rather than {@code ceil} is intentional: {@code sizeFor}
     * rounds up to the next power of two anyway, so the fractional part would only matter when
     * float fuzz pushes the result across a power-of-two boundary -- {@code ceil} would then double
     * the array size for no reason (e.g. {@code 12 * 4/3 = 16.0...0005f -> ceil 17 -> sizeFor 32}).
     *
     * @deprecated use {@link Hashtable#capacityFor(int)} (or {@link Hashtable#capacityFor(int,
     *     float)} for a load factor other than {@link Hashtable#DEFAULT_LOAD_FACTOR}), then {@link
     *     Hashtable#create(Class, int)} with the result.
     */
    @Deprecated
    @Nonnull
    public static Hashtable.Entry[] create(int requestedSize, float scale) {
      // Deliberately multiplies by `scale` rather than routing through
      // Hashtable#capacityFor(int, float), which divides by a load factor: `n * MAX_RATIO` and
      // `n / DEFAULT_LOAD_FACTOR` are not bit-identical in float, and this deprecated path keeps
      // its exact legacy sizing. Only the allocation itself is inverted onto the blessed API.
      return Hashtable.create((int) (requestedSize * scale));
    }

    /**
     * Inverse of a 75% load factor. Callers that size their bucket array from a target working-set
     * size {@code n} should pass {@code create(n, MAX_RATIO)} to leave ~25% headroom in the array.
     *
     * @deprecated equivalent to {@code 1f / Hashtable#DEFAULT_LOAD_FACTOR}; prefer {@link
     *     Hashtable#capacityFor(int)}, which applies that load factor directly.
     */
    @Deprecated public static final float MAX_RATIO = 1.0f / Hashtable.DEFAULT_LOAD_FACTOR;

    /**
     * @deprecated use {@link Hashtable#sizeFor(int)}.
     */
    @Deprecated
    static int sizeFor(int requestedSize) {
      return Hashtable.sizeFor(requestedSize);
    }

    /**
     * @deprecated use {@link Hashtable#clear(Hashtable.Entry[])}.
     */
    @Deprecated
    public static void clear(@Nonnull Hashtable.Entry[] buckets) {
      Hashtable.clear(buckets);
    }

    /**
     * @deprecated use {@link Hashtable#bucketIterator(Hashtable.Entry[], long)}.
     */
    @Deprecated
    @Nonnull
    public static <TEntry extends Hashtable.Entry> BucketIterator<TEntry> bucketIterator(
        @Nonnull Hashtable.Entry[] buckets, long keyHash) {
      return Hashtable.bucketIterator(buckets, keyHash);
    }

    /**
     * @deprecated use {@link Hashtable#mutatingBucketIterator(Hashtable.Entry[], long)}.
     */
    @Deprecated
    @Nonnull
    public static <TEntry extends Hashtable.Entry>
        MutatingBucketIterator<TEntry> mutatingBucketIterator(
            @Nonnull Hashtable.Entry[] buckets, long keyHash) {
      return Hashtable.mutatingBucketIterator(buckets, keyHash);
    }

    /**
     * @deprecated use {@link Hashtable#mutatingTableIterator(Hashtable.Entry[])}.
     */
    @Deprecated
    @Nonnull
    public static <TEntry extends Hashtable.Entry>
        MutatingTableIterator<TEntry> mutatingTableIterator(@Nonnull Hashtable.Entry[] buckets) {
      return Hashtable.mutatingTableIterator(buckets);
    }

    /**
     * @deprecated use {@link Hashtable#mutatingTableIterator(Hashtable.Entry[], int, int)}.
     */
    @Deprecated
    @Nonnull
    public static <TEntry extends Hashtable.Entry>
        MutatingTableIterator<TEntry> mutatingTableIterator(
            @Nonnull Hashtable.Entry[] buckets, int startBucket, int endBucket) {
      return Hashtable.mutatingTableIterator(buckets, startBucket, endBucket);
    }

    /**
     * @deprecated use {@link Hashtable#bucketIndex(Object[], long)}.
     */
    @Deprecated
    public static int bucketIndex(@Nonnull Object[] buckets, long keyHash) {
      return Hashtable.bucketIndex(buckets, keyHash);
    }

    /**
     * @deprecated use {@link Hashtable#insertHeadEntryAt(Hashtable.Entry[], int, Hashtable.Entry)}.
     */
    @Deprecated
    public static void insertHeadEntry(
        @Nonnull Hashtable.Entry[] buckets, int bucketIndex, @Nonnull Hashtable.Entry entry) {
      Hashtable.insertHeadEntryAt(buckets, bucketIndex, entry);
    }

    /**
     * @deprecated use {@link Hashtable#insertHeadEntryFor(Hashtable.Entry[], long,
     *     Hashtable.Entry)}.
     */
    @Deprecated
    public static void insertHeadEntry(
        @Nonnull Hashtable.Entry[] buckets, long keyHash, @Nonnull Hashtable.Entry entry) {
      Hashtable.insertHeadEntryFor(buckets, keyHash, entry);
    }

    /**
     * @deprecated use {@link Hashtable#bucketFor(Hashtable.Entry[], long)}.
     */
    @Deprecated
    @Nullable
    public static <TEntry extends Hashtable.Entry> TEntry bucket(
        @Nonnull Hashtable.Entry[] buckets, long keyHash) {
      return Hashtable.bucketFor(buckets, keyHash);
    }

    /**
     * @deprecated use {@link Hashtable#forEach(Hashtable.Entry[], Consumer)}.
     */
    @Deprecated
    public static <TEntry extends Hashtable.Entry> void forEach(
        @Nonnull Hashtable.Entry[] buckets, @Nonnull Consumer<? super TEntry> consumer) {
      Hashtable.forEach(buckets, consumer);
    }

    /**
     * @deprecated use {@link Hashtable#forEach(Hashtable.Entry[], Object, BiConsumer)}.
     */
    @Deprecated
    public static <C, TEntry extends Hashtable.Entry> void forEach(
        @Nonnull Hashtable.Entry[] buckets,
        C context,
        @Nonnull BiConsumer<? super C, ? super TEntry> consumer) {
      Hashtable.forEach(buckets, context, consumer);
    }
  }

  /**
   * Read-only iterator over entries in a single bucket whose {@code keyHash} matches a specific
   * search hash. Cheaper than {@link MutatingBucketIterator} because it does not track the
   * previous-node pointers required for splicing — use it when you only need to walk the chain.
   *
   * <p>For {@code remove} or {@code replace} operations, use {@link MutatingBucketIterator}
   * instead.
   *
   * <p>The chain-walk work to find the next-match entry happens in {@link #next()} (and in the
   * constructor for the first match); {@link #hasNext()} is an O(1) field read.
   */
  public static final class BucketIterator<TEntry extends Entry> implements Iterator<TEntry> {
    private final long keyHash;
    private Hashtable.Entry nextEntry;

    BucketIterator(@Nonnull Hashtable.Entry[] buckets, long keyHash) {
      this.keyHash = keyHash;
      Hashtable.Entry cur = buckets[Hashtable.bucketIndex(buckets, keyHash)];
      while (cur != null && cur.keyHash != keyHash) {
        cur = cur.next();
      }
      this.nextEntry = cur;
    }

    @Override
    public boolean hasNext() {
      return this.nextEntry != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    @Nonnull
    public TEntry next() {
      Hashtable.Entry cur = this.nextEntry;
      if (cur == null) {
        throw new NoSuchElementException("no next!");
      }

      Hashtable.Entry advance = cur.next();
      while (advance != null && advance.keyHash != keyHash) {
        advance = advance.next();
      }
      this.nextEntry = advance;

      return (TEntry) cur;
    }
  }

  /**
   * Mutating iterator over entries in a single bucket whose {@code keyHash} matches a specific
   * search hash. Supports {@link #remove()} and {@link #replace} to splice the chain in place.
   *
   * <p>Carries previous-node pointers for the current entry and the next-match entry so that {@code
   * remove} and {@code replace} can fix up the chain in O(1) without re-walking from the bucket
   * head. After {@code remove} or {@code replace}, iteration may continue with another {@link
   * #next()}.
   *
   * <p>The chain-walk work to find the next-match entry happens in {@link #next()} (and in the
   * constructor for the first match); {@link #hasNext()} is an O(1) field read.
   */
  public static final class MutatingBucketIterator<TEntry extends Entry>
      implements Iterator<TEntry> {
    private final long keyHash;

    private final Hashtable.Entry[] buckets;

    /** The entry prior to the last entry returned by next Used for mutating operations */
    private Hashtable.Entry curPrevEntry;

    /** The entry that was last returned by next */
    private Hashtable.Entry curEntry;

    /** The entry prior to the next entry */
    private Hashtable.Entry nextPrevEntry;

    /** The next entry to be returned by next */
    private Hashtable.Entry nextEntry;

    MutatingBucketIterator(@Nonnull Hashtable.Entry[] buckets, long keyHash) {
      this.buckets = buckets;
      this.keyHash = keyHash;

      int bucketIndex = Hashtable.bucketIndex(buckets, keyHash);
      Hashtable.Entry headEntry = this.buckets[bucketIndex];
      if (headEntry == null) {
        this.nextEntry = null;
        this.nextPrevEntry = null;

        this.curEntry = null;
        this.curPrevEntry = null;
      } else {
        Hashtable.Entry prev, cur;
        for (prev = null, cur = headEntry; cur != null; prev = cur, cur = cur.next()) {
          if (cur.keyHash == keyHash) {
            break;
          }
        }
        this.nextPrevEntry = prev;
        this.nextEntry = cur;

        this.curEntry = null;
        this.curPrevEntry = null;
      }
    }

    @Override
    public boolean hasNext() {
      return (this.nextEntry != null);
    }

    @Override
    @SuppressWarnings("unchecked")
    @Nonnull
    public TEntry next() {
      Hashtable.Entry curEntry = this.nextEntry;
      if (curEntry == null) {
        throw new NoSuchElementException("no next!");
      }

      this.curEntry = curEntry;
      this.curPrevEntry = this.nextPrevEntry;

      Hashtable.Entry prev, cur;
      for (prev = this.nextEntry, cur = this.nextEntry.next();
          cur != null;
          prev = cur, cur = prev.next()) {
        if (cur.keyHash == keyHash) {
          break;
        }
      }
      this.nextPrevEntry = prev;
      this.nextEntry = cur;

      return (TEntry) curEntry;
    }

    @Override
    public void remove() {
      Hashtable.Entry oldCurEntry = this.curEntry;
      if (oldCurEntry == null) {
        throw new IllegalStateException();
      }

      Hashtable.Entry oldNext = oldCurEntry.next();
      this.setPrevNext(oldNext);
      // Detach the removed entry from the chain so stale references can't traverse back into
      // the live chain and so a now-unreachable tail can be reclaimed by GC.
      oldCurEntry.setNext(null);

      // If the next match was directly after oldCurEntry, its predecessor is now
      // curPrevEntry (oldCurEntry was just unlinked from the chain).
      if (this.nextPrevEntry == oldCurEntry) {
        this.nextPrevEntry = this.curPrevEntry;
      }
      this.curEntry = null;
    }

    public void replace(@Nonnull TEntry replacementEntry) {
      Hashtable.Entry oldCurEntry = this.curEntry;
      if (oldCurEntry == null) {
        throw new IllegalStateException();
      }

      Hashtable.Entry oldNext = oldCurEntry.next();
      replacementEntry.setNext(oldNext);
      this.setPrevNext(replacementEntry);
      // Detach the replaced entry from the chain; the replacement now owns the chain slot.
      oldCurEntry.setNext(null);

      // If the next match was directly after oldCurEntry, its predecessor is now
      // the replacement entry (which took oldCurEntry's chain slot).
      if (this.nextPrevEntry == oldCurEntry) {
        this.nextPrevEntry = replacementEntry;
      }
      this.curEntry = replacementEntry;
    }

    void setPrevNext(@Nullable Hashtable.Entry nextEntry) {
      if (this.curPrevEntry == null) {
        Hashtable.Entry[] buckets = this.buckets;
        buckets[Hashtable.bucketIndex(buckets, this.keyHash)] = nextEntry;
      } else {
        this.curPrevEntry.setNext(nextEntry);
      }
    }
  }

  /**
   * Mutating iterator over every entry in a bucket array, regardless of hash. Supports {@link
   * #remove()} to unlink the entry last returned by {@link #next()}.
   *
   * <p>Walks buckets in array order; within a bucket, walks the chain head-to-tail. After {@code
   * remove}, iteration may continue with another {@link #next()}.
   *
   * <p>Use this for sweeps -- eviction, expunge, full-table cleanup -- that aren't keyed to a
   * specific hash. For per-bucket walks keyed to a search hash, use {@link MutatingBucketIterator}.
   */
  public static final class MutatingTableIterator<TEntry extends Entry>
      implements Iterator<TEntry> {
    private final Hashtable.Entry[] buckets;

    /** Exclusive upper bound for bucket indices visited by this iterator. */
    private final int endBucket;

    /**
     * Index of the bucket holding {@link #nextEntry} (or holding {@link #curEntry} after remove).
     */
    private int nextBucketIndex;

    /**
     * Predecessor of {@link #nextEntry}, or {@code null} when {@code nextEntry} is the bucket head.
     */
    private Hashtable.Entry nextPrevEntry;

    /** Next entry to be returned by {@link #next()}, or {@code null} if iteration is exhausted. */
    private Hashtable.Entry nextEntry;

    /**
     * Bucket index that held the entry last returned by {@code next}; {@code -1} after {@code
     * remove}.
     */
    private int curBucketIndex = -1;

    /**
     * Predecessor of the entry last returned by {@code next}, or {@code null} if it was the bucket
     * head.
     */
    private Hashtable.Entry curPrevEntry;

    /**
     * Entry last returned by {@code next}; {@code null} before any call and after {@code remove}.
     */
    private Hashtable.Entry curEntry;

    MutatingTableIterator(@Nonnull Hashtable.Entry[] buckets, int startBucket, int endBucket) {
      this.buckets = buckets;
      if (startBucket < 0 || startBucket > buckets.length) {
        throw new IndexOutOfBoundsException(
            "startBucket " + startBucket + " out of range [0, " + buckets.length + "]");
      }
      if (endBucket < startBucket || endBucket > buckets.length) {
        throw new IndexOutOfBoundsException(
            "endBucket "
                + endBucket
                + " out of range ["
                + startBucket
                + ", "
                + buckets.length
                + "]");
      }
      this.endBucket = endBucket;
      seekFromBucket(startBucket);
    }

    /**
     * Bucket index of the entry last returned by {@link #next()}, or {@code -1} if {@code next} has
     * not yet been called or the most recent call was {@link #remove()}. Useful for callers driving
     * a cursor — e.g. resumable eviction sweeps that want to remember where the last successful
     * removal landed.
     */
    public int currentBucket() {
      return this.curBucketIndex;
    }

    @Override
    public boolean hasNext() {
      return this.nextEntry != null;
    }

    @Override
    @SuppressWarnings("unchecked")
    @Nonnull
    public TEntry next() {
      Hashtable.Entry e = this.nextEntry;
      if (e == null) {
        throw new NoSuchElementException("no next!");
      }

      this.curEntry = e;
      this.curPrevEntry = this.nextPrevEntry;
      this.curBucketIndex = this.nextBucketIndex;

      Hashtable.Entry n = e.next();
      if (n != null) {
        this.nextPrevEntry = e;
        this.nextEntry = n;
      } else {
        // walked off the end of this bucket; pick up at the next non-empty bucket
        seekFromBucket(this.nextBucketIndex + 1);
      }
      return (TEntry) e;
    }

    @Override
    public void remove() {
      Hashtable.Entry oldCurEntry = this.curEntry;
      if (oldCurEntry == null) {
        throw new IllegalStateException();
      }

      Hashtable.Entry oldNext = oldCurEntry.next();
      if (this.curPrevEntry == null) {
        this.buckets[this.curBucketIndex] = oldNext;
      } else {
        this.curPrevEntry.setNext(oldNext);
      }
      // Detach the removed entry from the chain so stale references can't traverse back into
      // the live chain and so a now-unreachable tail can be reclaimed by GC.
      oldCurEntry.setNext(null);

      // If the next entry was the immediate chain successor of oldCurEntry, its predecessor is
      // now what came before oldCurEntry (oldCurEntry was just unlinked).
      if (this.nextPrevEntry == oldCurEntry) {
        this.nextPrevEntry = this.curPrevEntry;
      }
      this.curEntry = null;
    }

    /**
     * Advance {@code nextBucketIndex} / {@code nextEntry} to the first non-empty bucket {@code >=
     * from} within {@code [0, endBucket)}.
     */
    private void seekFromBucket(int from) {
      Hashtable.Entry[] thisBuckets = this.buckets;
      for (int i = from; i < this.endBucket; i++) {
        Hashtable.Entry head = thisBuckets[i];
        if (head != null) {
          this.nextBucketIndex = i;
          this.nextPrevEntry = null;
          this.nextEntry = head;
          return;
        }
      }
      this.nextEntry = null;
      this.nextPrevEntry = null;
    }
  }
}
