package datadog.trace.util;

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
 * building blocks on this class (see {@link #createFixedBuckets(Class, int)}, {@link
 * #bucketFor(Hashtable.Entry[], long)}, {@link #insertHeadEntryAt(Hashtable.Entry[], int,
 * Hashtable.Entry)}, and friends). The deprecated {@link Support} class is a thin facade over those
 * same statics, retained for source compatibility.
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
   * capacity, {@link #insert} returns {@code false} and {@link #getOrCreate} returns {@code null}
   * rather than adding more entries -- a lookup hit is still always returned even at capacity, the
   * cap only blocks new entries. Want your own eviction policy instead of a hard cap? Drop down to
   * {@link Hashtable.Support} and manage the bucket array yourself. Actual bucket-array length is
   * rounded up to the next power of two.
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
    private final SizeTracker sizeTracker;

    public D1(int capacity) {
      // Bucket array gets load-factor headroom over the strict entry cap below, so chains stay
      // short even at capacity; see Hashtable#createFixedBuckets's javadoc for the same idiom.
      this.buckets = new Hashtable.Entry[sizeFor((int) (capacity * 4 / 3f))];
      this.sizeTracker = new SizeTracker(capacity);
    }

    /**
     * Creates a single-key table with a fixed bucket count sized for {@code capacity} entries. The
     * {@code entryClass} pins the concrete entry type so the compiler infers both {@code K} and
     * {@code TEntry} at the call site -- e.g. {@code D1.createFixedBuckets(MyEntry.class, 64)} --
     * keeping the factory symmetric with the rest of the flat-collections family (see {@link
     * Hashtable#createFixedBuckets(Class, int)} for why the class isn't otherwise consumed).
     * Capacity is fixed; the table does not resize.
     */
    @Nonnull
    public static <K, TEntry extends D1.Entry<K>> D1<K, TEntry> createFixedBuckets(
        @Nonnull Class<TEntry> entryClass, int capacity) {
      return new D1<>(capacity);
    }

    public int size() {
      return this.sizeTracker.size();
    }

    /** {@code true} once {@link #size()} has reached this table's fixed capacity. */
    public boolean isFull() {
      return this.sizeTracker.isFull();
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
      long keyHash = D1.Entry.hash(key);

      for (MutatingBucketIterator<TEntry> iter = mutatingBucketIterator(this.buckets, keyHash);
          iter.hasNext(); ) {
        TEntry curEntry = iter.next();

        if (curEntry.matches(key)) {
          iter.remove();
          this.sizeTracker.decrement();
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
      if (!this.sizeTracker.tryReserve()) {
        return false;
      }
      insertHeadEntryFor(this.buckets, newEntry.keyHash, newEntry);
      return true;
    }

    /**
     * Replaces the existing entry for {@code newEntry}'s key (returning the prior entry), or
     * inserts it fresh (returning {@code null}) if absent. Replacing never grows {@link #size()},
     * so it always succeeds even on a full table; only a fresh insert can hit the cap, in which
     * case this throws {@link IllegalStateException} -- unlike {@link #insert} and {@link
     * #getOrCreate}, there is no spare return-value slot free to signal refusal without colliding
     * with the existing "freshly inserted" {@code null}.
     */
    @Nullable
    public TEntry insertOrReplace(@Nonnull TEntry newEntry) {
      for (MutatingBucketIterator<TEntry> iter =
              mutatingBucketIterator(this.buckets, newEntry.keyHash);
          iter.hasNext(); ) {
        TEntry curEntry = iter.next();

        if (curEntry.matches(newEntry.key)) {
          iter.replace(newEntry);
          return curEntry;
        }
      }

      if (!this.sizeTracker.tryReserve()) {
        throw new IllegalStateException(
            "Hashtable.D1 is at capacity (" + this.sizeTracker.capacity() + ")");
      }
      insertHeadEntryFor(this.buckets, newEntry.keyHash, newEntry);
      return null;
    }

    /**
     * Returns the entry for {@code key}, building one via {@code creator} if absent. Computes the
     * hash once and reuses it for both the lookup and (on miss) the insert -- avoids the
     * double-hash that "{@code get}; if null then {@code insert}" would incur.
     *
     * <p>The {@code creator} is expected to build an entry whose {@code keyHash} equals {@link
     * Entry#hash(Object) D1.Entry.hash(key)} -- typically by passing {@code key} to a constructor
     * that calls {@code super(key)}. A mismatched hash will leave the new entry inserted at a
     * bucket that future {@link #get} calls won't probe.
     *
     * <p>Returns {@code null} once the table is at capacity and {@code key} is absent -- a hit is
     * always returned even at capacity, the cap only blocks new entries.
     */
    @Nonnull
    public TEntry getOrCreate(
        @Nullable K key, @Nonnull Function<? super K, ? extends TEntry> creator) {
      long keyHash = D1.Entry.hash(key);
      for (TEntry curEntry = bucketFor(this.buckets, keyHash);
          curEntry != null;
          curEntry = curEntry.next()) {
        if (curEntry.keyHash == keyHash && curEntry.matches(key)) {
          return curEntry;
        }
      }
      if (this.sizeTracker.isFull()) {
        return null;
      }
      TEntry newEntry = creator.apply(key);
      insertHeadEntryFor(this.buckets, newEntry.keyHash, newEntry);
      this.sizeTracker.increment();
      return newEntry;
    }

    public void clear() {
      Hashtable.clear(this.buckets);
      this.sizeTracker.reset();
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

    /**
     * Removes every entry, passing each to {@code sink} as it is unlinked -- the read-and-reset
     * primitive for flush/publish workflows (drain the table into a telemetry batch, an event
     * emitter, etc.). Equivalent to {@link #forEach} then {@link #clear} in a single call.
     */
    public void drain(@Nonnull Consumer<? super TEntry> sink) {
      Hashtable.drain(this.buckets, sink);
      this.sizeTracker.reset();
    }

    /**
     * Context-passing {@link #drain(Consumer)}. Pass a non-capturing {@link BiConsumer} (typically
     * a {@code static final}) plus the accumulator as {@code context} to avoid a capturing-lambda
     * allocation.
     */
    public <C> void drain(C context, @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
      Hashtable.drain(this.buckets, context, sink);
      this.sizeTracker.reset();
    }
  }

  /**
   * Two-key (composite-key) hash table with chaining.
   *
   * <p>The user supplies a {@link D2.Entry} subclass carrying both key parts and any value fields.
   * Compared to {@code HashMap<Pair, V>} this avoids the per-lookup {@code Pair} (or record)
   * allocation: both key parts are passed directly through {@link #get}, {@link #remove}, {@link
   * #insert}, and {@link #insertOrReplace}. Combined with in-place value mutation, this makes
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
    private final SizeTracker sizeTracker;

    public D2(int capacity) {
      // Bucket array gets load-factor headroom over the strict entry cap below, so chains stay
      // short even at capacity; see Hashtable#createFixedBuckets's javadoc for the same idiom.
      this.buckets = new Hashtable.Entry[sizeFor((int) (capacity * 4 / 3f))];
      this.sizeTracker = new SizeTracker(capacity);
    }

    /**
     * Creates a composite-key table with a fixed bucket count sized for {@code capacity} entries.
     * The {@code entryClass} pins the concrete entry type so the compiler infers {@code K1}, {@code
     * K2}, and {@code TEntry} at the call site -- e.g. {@code D2.createFixedBuckets(MyEntry.class,
     * 64)} -- keeping the factory symmetric with the rest of the flat-collections family (see
     * {@link Hashtable#createFixedBuckets(Class, int)} for why the class isn't otherwise consumed).
     * Capacity is fixed; the table does not resize.
     */
    @Nonnull
    public static <K1, K2, TEntry extends D2.Entry<K1, K2>> D2<K1, K2, TEntry> createFixedBuckets(
        @Nonnull Class<TEntry> entryClass, int capacity) {
      return new D2<>(capacity);
    }

    public int size() {
      return this.sizeTracker.size();
    }

    /** {@code true} once {@link #size()} has reached this table's fixed capacity. */
    public boolean isFull() {
      return this.sizeTracker.isFull();
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
      long keyHash = D2.Entry.hash(key1, key2);

      for (MutatingBucketIterator<TEntry> iter = mutatingBucketIterator(this.buckets, keyHash);
          iter.hasNext(); ) {
        TEntry curEntry = iter.next();

        if (curEntry.matches(key1, key2)) {
          iter.remove();
          this.sizeTracker.decrement();
          return curEntry;
        }
      }

      return null;
    }

    /** Two-key analogue of {@link D1#insert}, with the same strict-cap refusal contract. */
    public boolean insert(@Nonnull TEntry newEntry) {
      if (!this.sizeTracker.tryReserve()) {
        return false;
      }
      insertHeadEntryFor(this.buckets, newEntry.keyHash, newEntry);
      return true;
    }

    /** Two-key analogue of {@link D1#insertOrReplace}, with the same refusal contract. */
    @Nullable
    public TEntry insertOrReplace(@Nonnull TEntry newEntry) {
      for (MutatingBucketIterator<TEntry> iter =
              mutatingBucketIterator(this.buckets, newEntry.keyHash);
          iter.hasNext(); ) {
        TEntry curEntry = iter.next();

        if (curEntry.matches(newEntry.key1, newEntry.key2)) {
          iter.replace(newEntry);
          return curEntry;
        }
      }

      if (!this.sizeTracker.tryReserve()) {
        throw new IllegalStateException(
            "Hashtable.D2 is at capacity (" + this.sizeTracker.capacity() + ")");
      }
      insertHeadEntryFor(this.buckets, newEntry.keyHash, newEntry);
      return null;
    }

    /**
     * Two-key analogue of {@link D1#getOrCreate}. Computes the combined hash once and reuses it for
     * both lookup and (on miss) insert. The {@code creator} is expected to build an entry whose
     * {@code keyHash} equals {@link Entry#hash(Object, Object) D2.Entry.hash(key1, key2)}. Same
     * strict-cap refusal contract as {@link D1#getOrCreate}.
     */
    @Nonnull
    public TEntry getOrCreate(
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
      if (this.sizeTracker.isFull()) {
        return null;
      }
      TEntry newEntry = creator.apply(key1, key2);
      insertHeadEntryFor(this.buckets, newEntry.keyHash, newEntry);
      this.sizeTracker.increment();
      return newEntry;
    }

    public void clear() {
      Hashtable.clear(this.buckets);
      this.sizeTracker.reset();
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

    /**
     * Removes every entry, passing each to {@code sink} as it is unlinked -- the read-and-reset
     * primitive for flush/publish workflows (drain the table into a telemetry batch, an event
     * emitter, etc.). Equivalent to {@link #forEach} then {@link #clear} in a single call.
     */
    public void drain(@Nonnull Consumer<? super TEntry> sink) {
      Hashtable.drain(this.buckets, sink);
      this.sizeTracker.reset();
    }

    /**
     * Context-passing {@link #drain(Consumer)}. Pass a non-capturing {@link BiConsumer} (typically
     * a {@code static final}) plus the accumulator as {@code context} to avoid a capturing-lambda
     * allocation.
     */
    public <C> void drain(C context, @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
      Hashtable.drain(this.buckets, context, sink);
      this.sizeTracker.reset();
    }
  }

  // ============================================================================================
  // Static building blocks over a caller-owned bucket array.
  //
  // Use these to assemble a custom table (higher arity, primitive keys, extra value fields) when
  // D1/D2 don't fit; D1/D2 delegate to them internally. This is the same "static functions over a
  // caller-owned array" shape as the concurrent variant (ConcurrentHashtable); see how
  // AggregateTable drives a Hashtable.Entry[] with these. The calling class owns the array and
  // exposes whatever operations it needs.
  //
  // Not thread-safe: there is no locking here. Concurrent access, including mixing reads with
  // writes, requires external synchronization.
  //
  // These were previously nested under the Support class; that class is now a deprecated facade
  // delegating here (retained for source compatibility with existing callers such as client-side
  // statistics).
  // ============================================================================================

  /** Upper bound on the bucket-array length returned by {@link #sizeFor(int)}. */
  static final int MAX_BUCKETS = 1 << 30;

  /**
   * Allocates a fixed-size bucket array sized to hold {@code capacity} entries: {@code capacity}
   * rounded up to the next power of two.
   *
   * <p>Returns a concrete {@code Hashtable.Entry[]} (chain heads are stored at the base type), so
   * the array assigns directly to a caller's {@code Hashtable.Entry[]} field. As with the
   * concurrent variant's {@code createFixedBuckets}, {@code entryClass} is <b>not</b> consumed to
   * allocate -- the array is a heterogeneous {@code Entry[]}, not a reflectively-allocated {@code
   * TEntry[]}. It is accepted only to keep the factory call-shape symmetric across the
   * flat-collections family ({@code createFixedBuckets(MyEntry.class, n)}). Capacity is fixed; the
   * table does not resize.
   *
   * <p>For load-factor headroom over a target working-set size, size {@code capacity} yourself
   * (e.g. {@code createFixedBuckets(MyEntry.class, (int) (n * 4 / 3f))}); the deprecated {@link
   * Support#create(int, float)} bundled that scaling but has no blessed equivalent.
   */
  @Nonnull
  public static <TEntry extends Entry> Hashtable.Entry[] createFixedBuckets(
      @Nonnull Class<TEntry> entryClass, int capacity) {
    return new Entry[sizeFor(capacity)];
  }

  /**
   * Rounds {@code requestedSize} up to the next power of two, capped at {@link #MAX_BUCKETS}, and
   * returns the bucket-array length to allocate. Throws {@link IllegalArgumentException} for
   * negative inputs or inputs above the cap. The concurrent variant shares this so the two families
   * round identically.
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
   * <p>Named to match {@link ConcurrentHashtable#bucketFor} rather than {@code bucket}: this class
   * has no competing {@code int}-index overload today, but naming it {@code bucketFor} up front
   * keeps the two classes' static building blocks aligned and avoids reintroducing the {@code
   * bucket}/{@code insertHeadEntry} int-vs-long overload ambiguity that {@link ConcurrentHashtable}
   * had to rename its way out of.
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
   * <p>Named distinctly from {@link #insertHeadEntryAt} (rather than overloaded on {@code long} vs.
   * {@code int}) for the same reason {@link ConcurrentHashtable#insertHeadEntryFor} is: a caller
   * with a primitive {@code int}-typed key hash calling an overloaded {@code
   * insertHeadEntry(buckets, intHash, entry)} would silently bind to the {@code int}-index overload
   * instead of widening to this one, treating the raw hash as an array index.
   */
  public static void insertHeadEntryFor(
      @Nonnull Hashtable.Entry[] buckets, long keyHash, @Nonnull Hashtable.Entry entry) {
    insertHeadEntryAt(buckets, bucketIndex(buckets, keyHash), entry);
  }

  public static void clear(@Nonnull Hashtable.Entry[] buckets) {
    Arrays.fill(buckets, null);
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
   * bucket range {@code [startBucket, endBucket)}. Useful for resumable sweeps -- e.g. cursor-based
   * eviction in {@code AggregateTable} -- where one call drives {@code [cursor, length)} and a
   * wrap-around call drives {@code [0, cursor)}. The iterator does <b>not</b> wrap around within a
   * single instance; callers compose two iterators when wrap-around is desired. An empty range
   * ({@code startBucket == endBucket}) produces an immediately exhausted iterator.
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

  /**
   * Tracks a live entry count against a fixed capacity. {@link D1} and {@link D2} use this
   * internally for their strict entry-count cap; other composers of the static building blocks
   * above -- e.g. client-side stats' {@code AggregateTable}, which drives a {@code
   * Hashtable.Entry[]} directly -- can reuse it instead of hand-rolling the same
   * increment/decrement/cap-check bookkeeping.
   *
   * <p>Not thread-safe, matching the rest of this class.
   */
  public static final class SizeTracker {
    private final int capacity;
    private int size;

    public SizeTracker(int capacity) {
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
     * is already fully built (nothing between the check and the increment can fail) -- e.g. {@link
     * D1#insert}. When building the entry is itself fallible (e.g. {@link D1#getOrCreate}'s {@code
     * creator}), check {@link #isFull()} first, do the fallible work, then call {@link
     * #increment()} only once linking actually succeeds.
     *
     * <p>Returning {@code false} here is not a final refusal -- it's the caller's cue to either
     * refuse the insert, or make room (e.g. evict a stale entry via {@link EvictionCursor}) and
     * retry.
     */
    public boolean tryReserve() {
      if (isFull()) {
        return false;
      }
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

    public void reset() {
      this.size = 0;
    }
  }

  /**
   * Resumable cursor for scanning a bucket array to evict entries under a caller-supplied {@link
   * Predicate}, without repeatedly re-scanning the same already-checked prefix on a sustained
   * eviction stream.
   *
   * <p>Pairs with {@link SizeTracker}: when {@link SizeTracker#tryReserve()} refuses because the
   * table is full, a composer can call {@link #evictOne} to make room and retry, or give up if
   * nothing was evictable. Factored out of client-side stats' {@code AggregateTable}, which
   * originally hand-rolled this same cursor-resumed two-pass scan.
   *
   * <p>Not thread-safe, matching the rest of this class.
   */
  public static final class EvictionCursor {
    private int cursor;

    /**
     * Scans {@code buckets} for the first entry matching {@code evictable}, starting at the cursor
     * and wrapping all the way around back to the cursor if needed. Unlinks and returns the evicted
     * entry, resuming the next call's scan from just past it; returns {@code null} if no entry
     * matched anywhere in the table.
     */
    @Nullable
    public Entry evictOne(
        @Nonnull Hashtable.Entry[] buckets, @Nonnull Predicate<? super Entry> evictable) {
      Entry evicted = evictOneInRange(buckets, evictable, this.cursor, buckets.length);
      if (evicted == null && this.cursor != 0) {
        evicted = evictOneInRange(buckets, evictable, 0, this.cursor);
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
     * Unlinks every entry matching {@code evictable} in a single full pass over {@code buckets},
     * regardless of the cursor's current position, and returns how many were removed. Resets the
     * cursor to the start, since a full pass leaves nothing later to resume from.
     */
    public int drain(
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
      this.cursor = 0;
      return count;
    }

    public void reset() {
      this.cursor = 0;
    }
  }

  /**
   * Bundles a bucket array together with a {@link SizeTracker} and {@link EvictionCursor} sized and
   * matched to it, so a composer driving the static building blocks directly (e.g. client-side
   * stats' {@code AggregateTable}) gets everything it needs to store from one factory call, instead
   * of separately sizing an array and a tracker that must stay in sync with it. Same headroom idiom
   * as {@link D1}/{@link D2}'s constructors: {@code capacity} is the strict cap on live entries,
   * and the backing array is sized with load-factor headroom over it.
   *
   * <p>Store the pieces of this bundle into your own fields; nothing here is meant to be held onto
   * as a {@code Table} itself.
   */
  public static final class Table {
    public final Hashtable.Entry[] buckets;
    public final SizeTracker size;
    public final EvictionCursor evictionCursor = new EvictionCursor();

    private Table(Hashtable.Entry[] buckets, int capacity) {
      this.buckets = buckets;
      this.size = new SizeTracker(capacity);
    }
  }

  /**
   * Creates a {@link Table}: a bucket array sized with load-factor headroom over {@code capacity},
   * paired with a {@link SizeTracker} capped at the strict {@code capacity} and a fresh {@link
   * EvictionCursor}.
   */
  @Nonnull
  public static Table createTable(int capacity) {
    Hashtable.Entry[] buckets = new Hashtable.Entry[sizeFor((int) (capacity * 4 / 3f))];
    return new Table(buckets, capacity);
  }

  /**
   * Deprecated facade over the static building blocks that are now methods on {@link Hashtable}
   * itself (mirroring the concurrent variant). Each method here delegates to its {@code
   * Hashtable.*} counterpart; the two sizing helpers with no blessed equivalent -- {@link
   * #create(int, float)} and {@link #MAX_RATIO} -- keep their real bodies here.
   *
   * <p>Retained only for source compatibility with existing callers (e.g. client-side statistics).
   * New code should call the {@code Hashtable.*} statics directly.
   *
   * @deprecated use the static building blocks on {@link Hashtable} directly.
   */
  @Deprecated
  public static final class Support {
    private Support() {}

    /**
     * @deprecated use {@link Hashtable#createFixedBuckets(Class, int)}.
     */
    @Deprecated
    @Nonnull
    public static Hashtable.Entry[] create(int requestedSize) {
      return new Entry[sizeFor(requestedSize)];
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
     * <p>No blessed equivalent: callers wanting load-factor headroom size the capacity themselves
     * and call {@link Hashtable#createFixedBuckets(Class, int)}.
     *
     * @deprecated size the capacity yourself and use {@link Hashtable#createFixedBuckets(Class,
     *     int)}.
     */
    @Deprecated
    @Nonnull
    public static Hashtable.Entry[] create(int requestedSize, float scale) {
      return new Entry[sizeFor((int) (requestedSize * scale))];
    }

    /**
     * Inverse of a 75% load factor. Callers that size their bucket array from a target working-set
     * size {@code n} should pass {@code create(n, MAX_RATIO)} to leave ~25% headroom in the array.
     */
    @Deprecated public static final float MAX_RATIO = 4.0f / 3.0f;

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
      Hashtable.Entry cur = buckets[Support.bucketIndex(buckets, keyHash)];
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

      int bucketIndex = Support.bucketIndex(buckets, keyHash);
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
        buckets[Support.bucketIndex(buckets, this.keyHash)] = nextEntry;
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
