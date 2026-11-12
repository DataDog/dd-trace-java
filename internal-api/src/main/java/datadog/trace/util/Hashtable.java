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
 * <p>Use {@code ConcurrentHashtable} for concurrent access. Otherwise use this class when entries
 * are removed independently, and {@code FlatHashtable} when the table is cleared as a unit.
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
   * <p>The table holds at most the {@code maxCapacity} passed to {@link #createBounded} and never
   * resizes, so choose a bound that fits the working set. At the bound, {@link #insert} returns
   * {@code false} and {@link #tryGetOrCreateOrNull} returns {@code null}; lookup hits are still
   * returned. For an eviction policy instead of a hard bound, drive the static building blocks with
   * {@link Hashtable#createBounded(int)}, whose {@link SizeManager} both counts and evicts.
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
     * Creates a single-key table bounded to {@code maxCapacity} live entries. At the bound, {@link
     * #insert} returns {@code false} and {@link #tryGetOrCreateOrNull} returns {@code null} for an
     * absent key; lookup hits and replacements still succeed.
     *
     * <p>The bucket array is allocated once via {@link Hashtable#capacityFor(int)}. Callers that
     * need an eviction policy can use {@link Hashtable#createBounded(int)} with its state-aware
     * reservation and eviction helpers.
     *
     * <p>{@code entryClass} is used only to infer {@code K} and {@code TEntry}; the backing array
     * remains a {@code Hashtable.Entry[]}.
     *
     * @param <K> key type
     * @param <TEntry> entry type
     * @param entryClass concrete entry type used for generic type inference
     * @param maxCapacity maximum number of live entries
     * @return a bounded table
     */
    @Nonnull
    public static <K, TEntry extends D1.Entry<K>> D1<K, TEntry> createBounded(
        @Nonnull Class<TEntry> entryClass, int maxCapacity) {
      return new D1<>(maxCapacity);
    }

    /**
     * Live entry count. Exact here, unlike {@link SizeManager#estimateSize()}: this class reserves
     * and links within a single call, so a caller can never observe the reservation window.
     */
    public int size() {
      return this.sizeManager.estimateSize();
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
      // Walks the chain directly instead of delegating to Hashtable#removeMatching: an
      // `e -> e.matches(key)` predicate captures `key`, so it would allocate on every call.
      long keyHash = D1.Entry.hash(key);
      for (MutatingBucketIterator<TEntry> iter = mutatingBucketIterator(this.buckets, keyHash);
          iter.hasNext(); ) {
        TEntry curEntry = iter.next();
        if (curEntry.matches(key)) {
          iter.remove();
          this.sizeManager.decrement(curEntry);
          return curEntry;
        }
      }
      return null;
    }

    /**
     * Removes every entry matching {@code predicate}, returning {@code true} if any were removed.
     */
    public boolean removeIf(@Nonnull Predicate<? super TEntry> predicate) {
      return Hashtable.removeIf(this.sizeManager, this.buckets, predicate);
    }

    /**
     * Unconditionally adds {@code newEntry} ({@code true}), or {@code false} if the table is
     * already at capacity. Caller-responsible: {@code newEntry}'s key must be absent, else it lands
     * shadowed behind the existing entry. {@code newEntry} must also be a fresh entry, not already
     * linked into this or any other table's bucket chain -- reinserting an already-linked entry
     * corrupts the chain it's still part of (guarded by an assertion in {@link
     * #insertHeadEntryAt(Hashtable.Entry[], int, Hashtable.Entry)}).
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
     * <p>It does not return the displaced entry; call {@link #get} first if you need it. Refusal at
     * the bound is ordinary steady-state behavior, so it is reported as {@code false} rather than
     * thrown.
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
          this.sizeManager.forget(curEntry);
          return true;
        }
      }

      return insertHeadEntryFor(this.sizeManager, this.buckets, newEntry.keyHash, newEntry);
    }

    /**
     * Returns the existing entry for {@code key}, or creates one with {@code creator}. The result
     * is absent only when the key is absent and the table is at capacity; lookup hits are always
     * returned.
     *
     * <p>The hash is computed once for lookup and insertion. {@code creator} must return an entry
     * whose {@code keyHash} equals {@link Entry#hash(Object) D1.Entry.hash(key)}, or subsequent
     * {@link #get} calls will not find it.
     *
     * <p>Delegating to {@link #tryGetOrCreateOrNull} keeps a single {@link Maybe#of} allocation
     * site.
     *
     * @param key key to look up
     * @param creator creates an entry when the key is absent
     * @return the existing or created entry, or an absent value when a new key is refused at
     *     capacity
     */
    @Nonnull
    public Maybe<TEntry> tryGetOrCreate(
        @Nullable K key, @Nonnull Function<? super K, ? extends TEntry> creator) {
      return Maybe.of(tryGetOrCreateOrNull(key, creator));
    }

    /**
     * Nullable form of {@link #tryGetOrCreate}, for call sites where a null check reads better than
     * {@link Maybe#update} or {@link Maybe#getOrNull}.
     *
     * @param key key to look up
     * @param creator creates an entry when the key is absent
     * @return the existing or created entry, or {@code null} when a new key is refused at capacity
     */
    @Nullable
    public TEntry tryGetOrCreateOrNull(
        @Nullable K key, @Nonnull Function<? super K, ? extends TEntry> creator) {
      long keyHash = D1.Entry.hash(key);
      for (TEntry curEntry = bucketFor(this.buckets, keyHash);
          curEntry != null;
          curEntry = curEntry.next()) {
        if (curEntry.keyHash == keyHash && curEntry.matches(key)) {
          return curEntry;
        }
      }
      // Build before incrementing because creator may throw; reserving first would leak capacity.
      if (this.sizeManager.isFull()) {
        return null;
      }
      TEntry newEntry = creator.apply(key);
      insertHeadEntryFor(this.buckets, newEntry.keyHash, newEntry);
      this.sizeManager.increment();
      return newEntry;
    }

    /**
     * {@link #tryGetOrCreateOrNull}, but evicting one entry matching {@code evictable} instead of
     * refusing when the table is full -- see {@link #tryGetOrCreateOrEvictOrNull} for the
     * null-returning form and the eviction/creation ordering.
     */
    @Nonnull
    public Maybe<TEntry> tryGetOrCreateOrEvict(
        @Nullable K key,
        @Nonnull Function<? super K, ? extends TEntry> creator,
        @Nonnull Predicate<? super TEntry> evictable) {
      return Maybe.of(tryGetOrCreateOrEvictOrNull(key, creator, evictable));
    }

    /**
     * Nullable form of {@link #tryGetOrCreateOrEvict}. When full, evicts before invoking {@code
     * creator}; if creation then throws, the table remains one entry smaller without leaking a
     * reservation.
     *
     * @param key key to look up
     * @param creator creates an entry when the key is absent
     * @param evictable selects an entry to remove when the table is full
     * @return the existing or created entry, or {@code null} when full with no evictable entry
     */
    @Nullable
    public TEntry tryGetOrCreateOrEvictOrNull(
        @Nullable K key,
        @Nonnull Function<? super K, ? extends TEntry> creator,
        @Nonnull Predicate<? super TEntry> evictable) {
      long keyHash = D1.Entry.hash(key);
      for (TEntry curEntry = bucketFor(this.buckets, keyHash);
          curEntry != null;
          curEntry = curEntry.next()) {
        if (curEntry.keyHash == keyHash && curEntry.matches(key)) {
          return curEntry;
        }
      }
      // Evict before creation because creator may throw; reserving the freed slot first could leak
      // it.
      if (this.sizeManager.isFull() && this.sizeManager.evictOne(this.buckets, evictable) == null) {
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
     * emitter, etc.). Equivalent to {@link #forEach} then {@link #clear} in a single call. If
     * {@code sink} throws, the entries it has not reached remain in the table.
     */
    public void drain(@Nonnull Consumer<? super TEntry> sink) {
      Hashtable.<TEntry>drain(this.sizeManager, this.buckets, sink);
    }

    /**
     * Context-passing {@link #drain(Consumer)}. Pass a non-capturing {@link BiConsumer} (typically
     * a {@code static final}) plus the accumulator as {@code context} to avoid a capturing-lambda
     * allocation.
     */
    public <C> void drain(C context, @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
      Hashtable.<C, TEntry>drain(this.sizeManager, this.buckets, context, sink);
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
     * Creates a two-key table bounded to {@code maxCapacity} live entries. At the bound, {@link
     * #insert} returns {@code false} and {@link #tryGetOrCreateOrNull} returns {@code null} for an
     * absent key pair; lookup hits and replacements still succeed.
     *
     * <p>{@code entryClass} is used only to infer the key and entry types; the backing array
     * remains a {@code Hashtable.Entry[]}.
     *
     * @param <K1> first key type
     * @param <K2> second key type
     * @param <TEntry> entry type
     * @param entryClass concrete entry type used for generic type inference
     * @param maxCapacity maximum number of live entries
     * @return a bounded table
     */
    @Nonnull
    public static <K1, K2, TEntry extends D2.Entry<K1, K2>> D2<K1, K2, TEntry> createBounded(
        @Nonnull Class<TEntry> entryClass, int maxCapacity) {
      return new D2<>(maxCapacity);
    }

    /**
     * Live entry count. Exact here, unlike {@link SizeManager#estimateSize()}: this class reserves
     * and links within a single call, so a caller can never observe the reservation window.
     */
    public int size() {
      return this.sizeManager.estimateSize();
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
          this.sizeManager.decrement(curEntry);
          return curEntry;
        }
      }
      return null;
    }

    /**
     * Removes every entry matching {@code predicate}, returning {@code true} if any were removed.
     */
    public boolean removeIf(@Nonnull Predicate<? super TEntry> predicate) {
      return Hashtable.removeIf(this.sizeManager, this.buckets, predicate);
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
          this.sizeManager.forget(curEntry);
          return true;
        }
      }

      return insertHeadEntryFor(this.sizeManager, this.buckets, newEntry.keyHash, newEntry);
    }

    /**
     * Returns the existing entry for {@code (key1, key2)}, or creates one with {@code creator}. The
     * result is absent only when the key pair is absent and the table is at capacity; lookup hits
     * are always returned.
     *
     * <p>The combined hash is computed once for lookup and insertion. {@code creator} must return
     * an entry whose {@code keyHash} equals {@link Entry#hash(Object, Object) D2.Entry.hash(key1,
     * key2)}, or subsequent {@link #get} calls will not find it.
     *
     * <p>Delegating to {@link #tryGetOrCreateOrNull} keeps a single {@link Maybe#of} call site.
     *
     * @param key1 first key component
     * @param key2 second key component
     * @param creator creates an entry when the key pair is absent
     * @return the existing or created entry, or an absent value when a new key pair is refused at
     *     capacity
     */
    @Nonnull
    public Maybe<TEntry> tryGetOrCreate(
        @Nullable K1 key1,
        @Nullable K2 key2,
        @Nonnull BiFunction<? super K1, ? super K2, ? extends TEntry> creator) {
      return Maybe.of(tryGetOrCreateOrNull(key1, key2, creator));
    }

    /**
     * Nullable form of {@link #tryGetOrCreate}, for call sites where a null check reads better than
     * {@link Maybe#update} or {@link Maybe#getOrNull}.
     *
     * @param key1 first key component
     * @param key2 second key component
     * @param creator creates an entry when the key pair is absent
     * @return the existing or created entry, or {@code null} when a new key pair is refused at
     *     capacity
     */
    @Nullable
    public TEntry tryGetOrCreateOrNull(
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
      // Build before incrementing because creator may throw; reserving first would leak capacity.
      if (this.sizeManager.isFull()) {
        return null;
      }
      TEntry newEntry = creator.apply(key1, key2);
      insertHeadEntryFor(this.buckets, newEntry.keyHash, newEntry);
      this.sizeManager.increment();
      return newEntry;
    }

    /**
     * Two-key analogue of {@link D1#tryGetOrCreateOrEvict}: {@link #tryGetOrCreateOrNull}, but
     * evicting one entry matching {@code evictable} instead of refusing when the table is full --
     * see {@link #tryGetOrCreateOrEvictOrNull} for the null-returning form and the
     * eviction/creation ordering.
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
     * Nullable form of {@link #tryGetOrCreateOrEvict}. When full, evicts before invoking {@code
     * creator}; if creation then throws, the table remains one entry smaller without leaking a
     * reservation.
     *
     * @param key1 first key component
     * @param key2 second key component
     * @param creator creates an entry when the key pair is absent
     * @param evictable selects an entry to remove when the table is full
     * @return the existing or created entry, or {@code null} when full with no evictable entry
     */
    @Nullable
    public TEntry tryGetOrCreateOrEvictOrNull(
        @Nullable K1 key1,
        @Nullable K2 key2,
        @Nonnull BiFunction<? super K1, ? super K2, ? extends TEntry> creator,
        @Nonnull Predicate<? super TEntry> evictable) {
      long keyHash = D2.Entry.hash(key1, key2);
      for (TEntry curEntry = bucketFor(this.buckets, keyHash);
          curEntry != null;
          curEntry = curEntry.next()) {
        if (curEntry.keyHash == keyHash && curEntry.matches(key1, key2)) {
          return curEntry;
        }
      }
      // Evict before creation because creator may throw; reserving the freed slot first could leak
      // it.
      if (this.sizeManager.isFull() && this.sizeManager.evictOne(this.buckets, evictable) == null) {
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
     * emitter, etc.). Equivalent to {@link #forEach} then {@link #clear} in a single call. If
     * {@code sink} throws, the entries it has not reached remain in the table.
     */
    public void drain(@Nonnull Consumer<? super TEntry> sink) {
      Hashtable.<TEntry>drain(this.sizeManager, this.buckets, sink);
    }

    /**
     * Context-passing {@link #drain(Consumer)}. Pass a non-capturing {@link BiConsumer} (typically
     * a {@code static final}) plus the accumulator as {@code context} to avoid a capturing-lambda
     * allocation.
     */
    public <C> void drain(C context, @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
      Hashtable.<C, TEntry>drain(this.sizeManager, this.buckets, context, sink);
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
   * Allocates a fixed-size bucket array with at least {@code capacity} buckets, rounded up to the
   * next power of two.
   *
   * <p>Erasure stops a caller writing {@code new TEntry[n]}, so {@code entryClass} is allocated
   * reflectively via {@link Array#newInstance}. That buys a real {@code TEntry} component type
   * rather than the base {@code Entry[]}: typed reads, real array-store checks, and a monomorphic
   * element type for the JIT. The one reflective call happens at construction, off any hot path.
   * Capacity is fixed; the table does not resize. Use {@link #create(int)} when the spine is driven
   * purely through the static building blocks and the base component type is enough.
   *
   * <p>{@code capacity} is a bucket count with no load-factor headroom. To size the array for a cap
   * on live entries, as {@link D1}, {@link D2}, and {@link #createBounded} do, pass {@link
   * #capacityFor(int)}: {@code create(MyEntry.class, capacityFor(cardinalityLimit))}.
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
   * {@code Hashtable.Entry[]} -- that is what {@link D1}, {@link D2}, and {@link #createBounded}
   * allocate internally. Prefer {@link #create(Class, int)} when you own the array and want a real
   * {@code TEntry} component type (typed reads, array-store checks, a monomorphic element type for
   * the JIT); prefer this one when a typed spine would only buy you covariant array-store checks on
   * every insert. Capacity is fixed; the table does not resize.
   *
   * <p>{@code buckets} is a bucket count, not an entry cap -- see {@link #capacityFor(int)} to
   * derive one from a target cap on live entries.
   */
  @Nonnull
  public static Hashtable.Entry[] create(int buckets) {
    return new Hashtable.Entry[sizeFor(buckets)];
  }

  /**
   * Default target load factor for chained bucket arrays. With well-spread hashes, {@code 0.75}
   * means 0.75 live entries per bucket on average. Separate chaining continues to work above 1.0,
   * degrading gradually into longer chains rather than reaching a correctness cliff.
   */
  public static final float DEFAULT_LOAD_FACTOR = 0.75f;

  /**
   * Bucket-array length for a strict cap of {@code cardinalityLimit} live entries at {@link
   * #DEFAULT_LOAD_FACTOR}: infers a reasonable bucket count from the entry cap you actually care
   * about, rather than making every caller redo the headroom math ({@link D1}, {@link D2}, and
   * {@link #createBounded} all size themselves this way). Pair with a {@link SizeManager} of {@code
   * cardinalityLimit} for the matching strict cap; this method only sizes the array.
   */
  public static int capacityFor(int cardinalityLimit) {
    return capacityFor(cardinalityLimit, DEFAULT_LOAD_FACTOR);
  }

  /**
   * {@link #capacityFor(int)} at an explicit {@code loadFactor} in {@code (0, 1)}: the bucket-array
   * length for a strict cap of {@code cardinalityLimit} live entries, rounded up to a power of two
   * via {@link #sizeFor(int)}.
   *
   * <p>The quotient is rounded up before {@link #sizeFor(int)}, so small limits keep their
   * headroom: a limit of 1 at a 0.75 load factor gets 2 buckets, not 1.
   */
  public static int capacityFor(int cardinalityLimit, float loadFactor) {
    if (!(loadFactor > 0f && loadFactor < 1f)) {
      throw new IllegalArgumentException("loadFactor must be in (0, 1): " + loadFactor);
    }
    return sizeFor((int) Math.ceil(cardinalityLimit / (double) loadFactor));
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
   * <p>The {@code For} suffix marks a key-hash argument, so an index-taking sibling can be added
   * without the {@code int}-vs-{@code long} overload trap described on {@link
   * #insertHeadEntryFor(Hashtable.Entry[], long, Hashtable.Entry)}.
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
    assert entry.next() == null
        : "Entry already linked -- inserting the same Entry instance twice corrupts the chain";
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
   * {@link #insertHeadEntryFor(Hashtable.Entry[], long, Hashtable.Entry)} with a strict-cap check:
   * reserves a slot from {@code sizeManager} and links {@code entry} only if that succeeds. Returns
   * {@code false}, leaving {@code buckets} unchanged, when the manager is at capacity.
   *
   * <p>Every size-tracked static takes {@code sizeManager} first, then the bucket array, the key,
   * and callbacks. Leading with the manager makes tracked and untracked calls differ at the start
   * of the argument list; forgetting the manager silently breaks the cap, so the difference should
   * be easy to spot in review.
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
   * {@link #insertHeadEntryFor(SizeManager, Hashtable.Entry[], long, Hashtable.Entry)} over a
   * {@link State}, which carries the spine and its manager together -- so there is no way to pass a
   * manager that belongs to a different table, and {@code TEntry} is inferred rather than needing a
   * witness at the call site.
   */
  public static <TEntry extends Entry> boolean insertHeadEntryFor(
      @Nonnull State<TEntry> state, long keyHash, @Nonnull TEntry entry) {
    return insertHeadEntryFor(state.sizeManager, state.buckets, keyHash, entry);
  }

  /**
   * Unlinks the first entry in {@code keyHash}'s chain that matches {@code matches}, decrements
   * {@code sizeManager}, and returns the entry. Returns {@code null}, changing nothing, when no
   * entry matches. This is the removal counterpart of {@link #insertHeadEntryFor(SizeManager,
   * Hashtable.Entry[], long, Hashtable.Entry)}, with the manager first for the same reason.
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
        sizeManager.decrement(curEntry);
        return curEntry;
      }
    }
    return null;
  }

  /**
   * {@link #drain(Hashtable.Entry[], Consumer)} with size tracking: decrements {@code sizeManager}
   * as each entry is unlinked, before {@code sink} receives it. If {@code sink} throws, the count
   * still matches the entries left in {@code buckets}.
   *
   * <p>It also restarts the eviction scan first. The saved scan position can point at a drained
   * entry, and {@code sink} may link that entry into another table.
   */
  public static <TEntry extends Entry> void drain(
      @Nonnull SizeManager sizeManager,
      @Nonnull Hashtable.Entry[] buckets,
      @Nonnull Consumer<? super TEntry> sink) {
    // Before any entry reaches the sink: the saved cursor may point at one, and the sink is free to
    // link it into another table, where a later eviction would follow it.
    sizeManager.clearCursor();
    drain(
        buckets,
        (Consumer<TEntry>)
            entry -> {
              sizeManager.decrement();
              sink.accept(entry);
            });
  }

  /** Context-passing form of {@link #drain(SizeManager, Hashtable.Entry[], Consumer)}. */
  public static <C, TEntry extends Entry> void drain(
      @Nonnull SizeManager sizeManager,
      @Nonnull Hashtable.Entry[] buckets,
      C context,
      @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
    sizeManager.clearCursor();
    drain(
        buckets,
        context,
        (BiConsumer<C, TEntry>)
            (ctx, entry) -> {
              sizeManager.decrement();
              sink.accept(ctx, entry);
            });
  }

  /** {@link #drain(SizeManager, Hashtable.Entry[], Consumer)} over a {@link State}. */
  public static <TEntry extends Entry> void drain(
      @Nonnull State<TEntry> state, @Nonnull Consumer<? super TEntry> sink) {
    drain(state.sizeManager, state.buckets, sink);
  }

  /** Context-passing form of {@link #drain(State, Consumer)}. */
  public static <C, TEntry extends Entry> void drain(
      @Nonnull State<TEntry> state,
      C context,
      @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
    drain(state.sizeManager, state.buckets, context, sink);
  }

  /** Live entries in {@code state}; see {@link SizeManager#estimateSize()} for why an estimate. */
  public static int estimateSize(@Nonnull State<?> state) {
    return state.sizeManager.estimateSize();
  }

  /**
   * {@code true} when {@code state}'s tracked count is zero. The count can include an outstanding
   * reservation or miss a link made without one (see {@link SizeManager#estimateSize()}), so use
   * this to skip work that would be wasted on an empty table, not to prove the table is empty.
   */
  public static boolean isLikelyEmpty(@Nonnull State<?> state) {
    return state.sizeManager.estimateSize() == 0;
  }

  /**
   * Head entry of the bucket {@code keyHash} maps to in {@code state}, typed to the state's entry
   * type so the chain walk at the call site needs no cast or witness.
   */
  @Nullable
  public static <TEntry extends Entry> TEntry bucketFor(
      @Nonnull State<TEntry> state, long keyHash) {
    return bucketFor(state.buckets, keyHash);
  }

  /**
   * Splices {@code entry} in as the new head of its bucket <em>without</em> touching the count,
   * because the caller already holds a reservation for it -- from {@link #tryReserveOrEvict} or a
   * bare {@link SizeManager#tryReserve()}. Pairing those is the shape of a miss path that wants to
   * refuse before it allocates:
   *
   * <pre>{@code
   * if (!tryReserveOrEvict(state, STALE)) {
   *   return null;                       // refused -- no entry was built
   * }
   * insertReserved(state, keyHash, buildEntry());
   * }</pre>
   *
   * <p>Distinct from {@link #insertHeadEntryFor(State, long, Entry)}, which reserves as it inserts;
   * calling that one here would count the entry twice.
   */
  public static <TEntry extends Entry> void insertReserved(
      @Nonnull State<TEntry> state, long keyHash, @Nonnull TEntry entry) {
    insertHeadEntryFor(state.buckets, keyHash, entry);
  }

  /** {@link #forEach(Hashtable.Entry[], Consumer)} over a {@link State}. */
  public static <TEntry extends Entry> void forEach(
      @Nonnull State<TEntry> state, @Nonnull Consumer<? super TEntry> consumer) {
    Hashtable.<TEntry>forEach(state.buckets, consumer);
  }

  /** {@link #forEach(Hashtable.Entry[], Object, BiConsumer)} over a {@link State}. */
  public static <C, TEntry extends Entry> void forEach(
      @Nonnull State<TEntry> state,
      C context,
      @Nonnull BiConsumer<? super C, ? super TEntry> consumer) {
    Hashtable.<C, TEntry>forEach(state.buckets, context, consumer);
  }

  /**
   * Reserves a slot in {@code state} for a fresh insert, evicting one entry matching {@code
   * evictable} if the table is full. {@code false} means full with nothing evictable -- the caller
   * should drop the datum. The whole capacity decision of a self-evicting table's miss path in one
   * call; pass a non-capturing {@code evictable} (typically a {@code static final}) to keep it
   * allocation-free.
   */
  public static <TEntry extends Entry> boolean tryReserveOrEvict(
      @Nonnull State<TEntry> state, @Nonnull Predicate<? super TEntry> evictable) {
    return state.sizeManager.tryReserveOrEvict(state.buckets, evictable);
  }

  /**
   * Unlinks the first entry in {@code state} matching {@code evictable}, resuming from where the
   * last eviction looked, and decrements the count. {@code null} if nothing matched anywhere.
   */
  @Nullable
  public static <TEntry extends Entry> TEntry evictOne(
      @Nonnull State<TEntry> state, @Nonnull Predicate<? super TEntry> evictable) {
    return state.sizeManager.evictOne(state.buckets, evictable);
  }

  /**
   * Unlinks every entry in {@code state} matching {@code evictable}, decrementing per removal, and
   * returns how many went.
   */
  public static <TEntry extends Entry> int evictAll(
      @Nonnull State<TEntry> state, @Nonnull Predicate<? super TEntry> evictable) {
    return state.sizeManager.evictAll(state.buckets, evictable);
  }

  /**
   * Removes every entry matching {@code predicate} from {@code buckets}, decrementing {@code
   * sizeManager} once per removal, and returns {@code true} if any were removed. It uses the same
   * sweep as {@link SizeManager#evictAll}, so it also restarts the eviction scan.
   */
  public static <TEntry extends Entry> boolean removeIf(
      @Nonnull SizeManager sizeManager,
      @Nonnull Hashtable.Entry[] buckets,
      @Nonnull Predicate<? super TEntry> predicate) {
    return sizeManager.evictAll(buckets, predicate) > 0;
  }

  /** {@link #removeIf(SizeManager, Hashtable.Entry[], Predicate)} over a {@link State}. */
  public static <TEntry extends Entry> boolean removeIf(
      @Nonnull State<TEntry> state, @Nonnull Predicate<? super TEntry> predicate) {
    return removeIf(state.sizeManager, state.buckets, predicate);
  }

  /** {@link #clear(SizeManager, Hashtable.Entry[])} over a {@link State}. */
  public static void clear(@Nonnull State<?> state) {
    clear(state.sizeManager, state.buckets);
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
   * bucket range {@code [startBucket, endBucket)}, for resumable sweeps such as {@link
   * SizeManager#evictOne}, which scans from its cursor to the end and then wraps around to the
   * start. A single iterator never wraps; callers compose two. An empty range ({@code startBucket
   * == endBucket}) produces an immediately exhausted iterator.
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
   * Variant of {@link #mutatingTableIterator(Hashtable.Entry[], int, int)} that resumes {@code
   * bucket} after {@code resumeAfter} instead of at its head -- see {@link
   * MutatingTableIterator#MutatingTableIterator(Hashtable.Entry[], int, int, Hashtable.Entry)}.
   */
  @Nonnull
  public static <TEntry extends Hashtable.Entry>
      MutatingTableIterator<TEntry> resumingTableIterator(
          @Nonnull Hashtable.Entry[] buckets,
          int bucket,
          int endBucket,
          @Nullable Hashtable.Entry resumeAfter) {
    return new MutatingTableIterator<TEntry>(buckets, bucket, endBucket, resumeAfter);
  }

  /**
   * {@link #removeMatching(SizeManager, Hashtable.Entry[], long, Predicate)} over a {@link State}.
   * The predicate is typed to {@code TEntry}, so a caller matching on entry fields needs no cast.
   */
  @Nullable
  public static <TEntry extends Entry> TEntry removeMatching(
      @Nonnull State<TEntry> state, long keyHash, @Nonnull Predicate<? super TEntry> matches) {
    return removeMatching(state.sizeManager, state.buckets, keyHash, matches);
  }

  /**
   * Empties {@code buckets} without touching any {@link SizeManager}. For a size-tracked table, use
   * {@link #clear(SizeManager, Hashtable.Entry[])}: clearing only the buckets leaves the count
   * consumed and the eviction cursor pointing into the discarded chains, so a later {@link
   * SizeManager#evictOne} could unlink an entry that is no longer in the table.
   */
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
   * an event emitter, etc.). Equivalent to {@link #forEach} then {@link #clear} in one call. Each
   * entry reaches {@code sink} with its chain link cleared. If {@code sink} throws, the entries it
   * has not reached remain in {@code buckets}.
   */
  @SuppressWarnings("unchecked")
  public static <TEntry extends Entry> void drain(
      @Nonnull Hashtable.Entry[] buckets, @Nonnull Consumer<? super TEntry> sink) {
    for (int i = 0; i < buckets.length; i++) {
      Entry entry = buckets[i];
      while (entry != null) {
        // Unhook before handing over: a sink that retains one entry of a chain would otherwise pin
        // the whole chain through `next`, including entries it chose not to keep. Read `next`
        // first, since the sink may do anything with the entry once it has it. The bucket advances
        // one entry at a time, so if the sink throws, the entries it has not reached stay linked.
        Entry next = entry.next();
        buckets[i] = next;
        entry.setNext(null);
        sink.accept((TEntry) entry);
        entry = next;
      }
    }
  }

  /**
   * Context-passing variant of {@link #drain(Hashtable.Entry[], Consumer)}. Pass a non-capturing
   * {@link BiConsumer} (typically a {@code static final}) plus the accumulator as {@code context}
   * (e.g. the target list or event builder) to avoid a capturing-lambda allocation.
   */
  @SuppressWarnings("unchecked")
  public static <C, TEntry extends Entry> void drain(
      @Nonnull Hashtable.Entry[] buckets,
      C context,
      @Nonnull BiConsumer<? super C, ? super TEntry> sink) {
    for (int i = 0; i < buckets.length; i++) {
      Entry entry = buckets[i];
      while (entry != null) {
        Entry next = entry.next();
        buckets[i] = next;
        entry.setNext(null);
        sink.accept(context, (TEntry) entry);
        entry = next;
      }
    }
  }

  /**
   * Tracks occupancy against a fixed capacity and owns the cursor used for eviction. Callers
   * driving raw bucket arrays must keep reservations and releases paired with successful table
   * mutations.
   *
   * <p>{@link D1} and {@link D2} use this internally; custom tables can compose it with the static
   * helpers. Not thread-safe.
   */
  public static final class SizeManager {
    private final int capacity;
    private int size;

    /**
     * Bucket where the next eviction scan starts: the bucket of the last eviction, moved on by one
     * after each refused scan. Starting here keeps a sustained eviction stream from re-testing the
     * same entries near bucket 0.
     */
    private int cursor;

    /**
     * Predecessor of the last evicted entry in {@code cursor}'s chain, or {@code null} when the
     * next scan starts at the bucket head. A saved predecessor lets the next scan resume mid-chain
     * instead of re-testing a non-evictable prefix, which would make repeated evictions from one
     * bucket quadratic. See {@link
     * Hashtable.MutatingTableIterator#MutatingTableIterator(Hashtable.Entry[], int, int,
     * Hashtable.Entry)} for what happens if this entry is removed before the next resume.
     */
    @Nullable private Hashtable.Entry cursorPrev;

    public SizeManager(int capacity) {
      this.capacity = capacity;
    }

    /**
     * Returns tracked occupancy. It temporarily includes a reserved but not yet linked slot and can
     * drift if callers mutate buckets without updating this manager. {@link D1} and {@link D2}
     * expose an exact size because mutation and tracking occur within one call.
     */
    public int estimateSize() {
      return this.size;
    }

    public int capacity() {
      return this.capacity;
    }

    /** {@code true} once {@link #estimateSize()} has reached {@link #capacity()}. */
    public boolean isFull() {
      return this.size >= this.capacity;
    }

    /**
     * Reserves a slot by incrementing the count, or returns {@code false} without changing it when
     * full. Use this only when no fallible work remains before linking; otherwise perform that
     * work, check {@link #isFull()} first, then link and call {@link #increment()}.
     *
     * @return whether a slot was reserved
     */
    public boolean tryReserve() {
      if (isFull()) {
        return false;
      }
      this.size += 1;
      return true;
    }

    /**
     * Reserves a slot or, when full, evicts the first entry matching {@code evictable}. Returns
     * {@code false} without modifying {@code buckets} when the table is full and nothing matches.
     *
     * @param buckets table to evict from
     * @param evictable selects an entry to remove
     * @return whether a slot was reserved
     */
    public <TEntry extends Entry> boolean tryReserveOrEvict(
        @Nonnull Hashtable.Entry[] buckets, @Nonnull Predicate<? super TEntry> evictable) {
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

    /**
     * Call after successfully unlinking {@code unlinked}. Prefer this to {@link #decrement()}: if
     * the eviction scan was saved on {@code unlinked}, the scan restarts at that bucket's head, so
     * it cannot follow the entry into another table the caller links it into.
     */
    public void decrement(@Nonnull Hashtable.Entry unlinked) {
      this.size -= 1;
      forget(unlinked);
    }

    /** Drops the saved scan position when it is {@code unlinked}, an entry leaving the table. */
    void forget(@Nonnull Hashtable.Entry unlinked) {
      if (unlinked == this.cursorPrev) {
        this.cursorPrev = null;
      }
    }

    /** Zeroes both the live count and the eviction scan position. */
    public void reset() {
      this.size = 0;
      clearCursor();
    }

    /** Restarts the eviction scan from the first bucket, dropping any saved mid-chain position. */
    private void clearCursor() {
      this.cursor = 0;
      this.cursorPrev = null;
    }

    /**
     * Scans {@code buckets} for the first entry matching {@code evictable}, starting where the last
     * eviction left off and wrapping around if needed. Unlinks and returns the evicted entry,
     * decrementing the count; returns {@code null} (count untouched) if nothing matched anywhere.
     *
     * <p>Each call resumes right after the previous removal, including mid-chain via {@link
     * #cursorPrev}. Without that, a bucket with a non-evictable prefix followed by many evictable
     * entries would re-test the prefix on every eviction, making N evictions cost O(N * prefix).
     * When a scan that resumed mid-chain wraps around, it rescans the cursor bucket from its head,
     * so prefix entries that have since become evictable are still found.
     *
     * <p><b>The amortization covers successful evictions only.</b> A refusal tests every live
     * entry, so a full table with nothing evictable pays a full pass per call. The cursor still
     * moves on, so successive refusals start from different buckets. Size the cap to the
     * steady-state working set and keep {@code evictable} cheap, because it runs once per live
     * entry on every refusal.
     */
    @SuppressWarnings("unchecked")
    @Nullable
    public <TEntry extends Entry> TEntry evictOne(
        @Nonnull Hashtable.Entry[] buckets, @Nonnull Predicate<? super TEntry> evictable) {
      Entry resumeAfter = this.cursorPrev;
      Entry evicted = evictOneInRange(buckets, evictable, this.cursor, buckets.length, resumeAfter);
      if (evicted == null) {
        // Wrap around. A mid-chain resume skipped the cursor bucket's prefix, whose entries may
        // have become evictable since, so the wrap then covers that bucket again from its head.
        int wrapEnd = resumeAfter != null ? this.cursor + 1 : this.cursor;
        if (wrapEnd != 0) {
          evicted = evictOneInRange(buckets, evictable, 0, wrapEnd, null);
        }
      }
      if (evicted != null) {
        this.size -= 1;
        return (TEntry) evicted;
      }
      // Nothing matched. Move the cursor on anyway so successive refusals start from different
      // buckets; buckets.length is a power of two, so the mask wraps. A full pass leaves no
      // mid-chain resume point.
      this.cursor = (this.cursor + 1) & (buckets.length - 1);
      this.cursorPrev = null;
      return null;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private <TEntry extends Entry> Entry evictOneInRange(
        @Nonnull Hashtable.Entry[] buckets,
        @Nonnull Predicate<? super TEntry> evictable,
        int startBucket,
        int endBucket,
        @Nullable Entry resumeAfter) {
      MutatingTableIterator<Entry> iter =
          resumeAfter != null
              ? resumingTableIterator(buckets, startBucket, endBucket, resumeAfter)
              : mutatingTableIterator(buckets, startBucket, endBucket);
      while (iter.hasNext()) {
        Entry candidate = iter.next();
        if (evictable.test((TEntry) candidate)) {
          int bucket = iter.currentBucket();
          Entry prev = iter.currentPrev();
          iter.remove();
          this.cursor = bucket;
          this.cursorPrev = prev;
          return candidate;
        }
      }
      return null;
    }

    /**
     * Removes every entry matching {@code evictable}, decrementing the tracked count per removal.
     * Resets the eviction cursor after a completed pass.
     *
     * @return the number of removed entries
     */
    @SuppressWarnings("unchecked")
    public <TEntry extends Entry> int evictAll(
        @Nonnull Hashtable.Entry[] buckets, @Nonnull Predicate<? super TEntry> evictable) {
      int count = 0;
      MutatingTableIterator<Entry> iter = mutatingTableIterator(buckets);
      while (iter.hasNext()) {
        Entry candidate = iter.next();
        if (evictable.test((TEntry) candidate)) {
          iter.remove();
          // Decrement immediately so the count remains correct if a later predicate call throws.
          this.size -= 1;
          count++;
        }
      }
      this.cursor = 0;
      this.cursorPrev = null;
      return count;
    }
  }

  /**
   * Mutable state for a caller-driven table, keeping its bucket array and {@link SizeManager}
   * together for overloads that accept a {@link State}.
   *
   * <p>Prefer passing this object directly to those overloads. Direct field access remains
   * available for low-level composition; callers using it must keep occupancy accounting consistent
   * with bucket mutations.
   *
   * <p>The {@code maxCapacity} passed to {@link #createBounded(int)} is the strict live-entry
   * bound, and the backing array is sized from it via {@link #capacityFor(int)}.
   *
   * @param <TEntry> stored entry type
   */
  public static final class State<TEntry extends Entry> {
    final Hashtable.Entry[] buckets;
    final SizeManager sizeManager;

    private State(Hashtable.Entry[] buckets, int maxCapacity) {
      this.buckets = buckets;
      this.sizeManager = new SizeManager(maxCapacity);
    }
  }

  /**
   * Creates a {@link State}: a bucket array sized with load-factor headroom over {@code
   * maxCapacity}, paired with a {@link SizeManager} capped at the strict {@code maxCapacity}.
   */
  @Nonnull
  public static <TEntry extends Entry> State<TEntry> createBounded(int maxCapacity) {
    Hashtable.Entry[] buckets = create(capacityFor(maxCapacity));
    return new State<>(buckets, maxCapacity);
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
      assert replacementEntry.next() == null
          : "Entry already linked -- inserting the same Entry instance twice corrupts the chain";

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
     * Bucket index that held the entry last returned by {@code next}; {@code -1} before the first
     * {@code next}. {@code remove} leaves it unchanged.
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
     * Resumes a walk of {@code bucket} immediately after {@code resumeAfter} instead of at the
     * bucket's head, so a caller that remembers where it stopped inside a chain does not re-test
     * the entries before that point. The successor is read when the walk starts, so other removals
     * in the chain since {@code resumeAfter} was saved are handled.
     *
     * <p>If {@code resumeAfter} itself was removed, removal cleared its link, so this walk treats
     * {@code bucket} as exhausted and continues at {@code bucket + 1}. {@link SizeManager#evictOne}
     * covers that skipped part when it wraps around. {@code resumeAfter} must not have been linked
     * into another chain since it was saved, or the walk would follow that chain; {@link
     * SizeManager} restarts its scan before a tracked drain hands entries out for that reason.
     */
    MutatingTableIterator(
        @Nonnull Hashtable.Entry[] buckets,
        int bucket,
        int endBucket,
        @Nullable Hashtable.Entry resumeAfter) {
      this.buckets = buckets;
      this.endBucket = endBucket;
      Hashtable.Entry head = (resumeAfter == null) ? buckets[bucket] : resumeAfter.next();
      if (head != null) {
        this.nextBucketIndex = bucket;
        this.nextPrevEntry = resumeAfter;
        this.nextEntry = head;
      } else {
        seekFromBucket(bucket + 1);
      }
    }

    /**
     * Bucket index of the entry last returned by {@link #next()}, or {@code -1} if {@code next} has
     * not yet been called. {@link #remove()} leaves it unchanged, so a resumable eviction sweep can
     * read it before or after removing to remember where the removal landed.
     */
    public int currentBucket() {
      return this.curBucketIndex;
    }

    /**
     * Predecessor of the entry last returned by {@link #next()} within its bucket, or {@code null}
     * if it was the bucket head. Paired with {@link #currentBucket()}, this is enough for a caller
     * to resume this exact chain position later via the {@code resumeAfter} constructor, without
     * re-walking the entries already tested.
     */
    @Nullable
    public Hashtable.Entry currentPrev() {
      return this.curPrevEntry;
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
