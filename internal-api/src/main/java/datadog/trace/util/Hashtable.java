package datadog.trace.util;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

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
 * support class to ease the implementation complexity.
 *
 * <p>This outer class is a pure namespace -- it can't be instantiated. The actual table types are
 * {@link D1}, {@link D2}, and (for higher-arity callers) {@link Support}-driven custom tables.
 */
public final class Hashtable {
  private Hashtable() {}

  /**
   * Internal base class for entries. Stores the precomputed 64-bit keyHash and the chain-next
   * pointer used to link colliding entries within a single bucket.
   *
   * <p>Subclasses add the actual key field(s) and a {@code matches(...)} method tailored to their
   * key arity. See {@link D1.Entry} and {@link D2.Entry}; for higher arities, client code can
   * subclass this directly and use {@link Support} to drive the table mechanics.
   */
  public abstract static class Entry {
    public final long keyHash;
    private Entry next = null;

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
   * for choosing a capacity appropriate to the working set. Actual bucket-array length is rounded
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

      protected Entry(K key) {
        super(hash(key));
        this.key = key;
      }

      public boolean matches(Object key) {
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
      public static long hash(Object key) {
        return (key == null) ? Long.MIN_VALUE : key.hashCode();
      }
    }

    // Package-private so iterator tests in the same package can drive Support.bucketIterator and
    // friends directly against the table's bucket array.
    final Hashtable.Entry[] buckets;
    private int size;

    public D1(int capacity) {
      this.buckets = Support.create(capacity);
      this.size = 0;
    }

    public int size() {
      return this.size;
    }

    public TEntry get(K key) {
      long keyHash = D1.Entry.hash(key);
      for (TEntry te = Support.bucket(this.buckets, keyHash); te != null; te = te.next()) {
        if (te.keyHash == keyHash && te.matches(key)) {
          return te;
        }
      }
      return null;
    }

    public TEntry remove(K key) {
      long keyHash = D1.Entry.hash(key);

      for (MutatingBucketIterator<TEntry> iter =
              Support.mutatingBucketIterator(this.buckets, keyHash);
          iter.hasNext(); ) {
        TEntry curEntry = iter.next();

        if (curEntry.matches(key)) {
          iter.remove();
          this.size -= 1;
          return curEntry;
        }
      }

      return null;
    }

    public void insert(TEntry newEntry) {
      Support.insertHeadEntry(this.buckets, newEntry.keyHash, newEntry);
      this.size += 1;
    }

    public TEntry insertOrReplace(TEntry newEntry) {
      for (MutatingBucketIterator<TEntry> iter =
              Support.mutatingBucketIterator(this.buckets, newEntry.keyHash);
          iter.hasNext(); ) {
        TEntry curEntry = iter.next();

        if (curEntry.matches(newEntry.key)) {
          iter.replace(newEntry);
          return curEntry;
        }
      }

      Support.insertHeadEntry(this.buckets, newEntry.keyHash, newEntry);
      this.size += 1;
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
     */
    public TEntry getOrCreate(K key, Function<? super K, ? extends TEntry> creator) {
      long keyHash = D1.Entry.hash(key);
      for (TEntry te = Support.bucket(this.buckets, keyHash); te != null; te = te.next()) {
        if (te.keyHash == keyHash && te.matches(key)) {
          return te;
        }
      }
      TEntry newEntry = creator.apply(key);
      Support.insertHeadEntry(this.buckets, newEntry.keyHash, newEntry);
      this.size += 1;
      return newEntry;
    }

    public void clear() {
      Support.clear(this.buckets);
      this.size = 0;
    }

    public void forEach(Consumer<? super TEntry> consumer) {
      Support.forEach(this.buckets, consumer);
    }

    /**
     * Context-passing forEach. Useful for callers that want to avoid a capturing-lambda allocation
     * -- pass a non-capturing {@link BiConsumer} (typically a {@code static final}) plus whatever
     * side-band state it needs as {@code context}.
     */
    public <T> void forEach(T context, BiConsumer<? super T, ? super TEntry> consumer) {
      Support.forEach(this.buckets, context, consumer);
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
   * <p>Capacity is fixed at construction; the table does not resize. Actual bucket-array length is
   * rounded up to the next power of two.
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

      protected Entry(K1 key1, K2 key2) {
        super(hash(key1, key2));
        this.key1 = key1;
        this.key2 = key2;
      }

      public boolean matches(K1 key1, K2 key2) {
        return Objects.equals(this.key1, key1) && Objects.equals(this.key2, key2);
      }

      /**
       * Returns the 64-bit lookup hash combining both key parts via {@link
       * LongHashingUtils#hash(Object, Object)}. Null parts contribute {@code 0} (not a sentinel,
       * unlike {@link D1.Entry#hash(Object)}): the combined hash can collide with real-key
       * combinations whose chained hash equals {@code hash(0, 0) = 0} or similar values. {@link
       * #matches(Object, Object)} resolves any such collision.
       */
      public static long hash(Object key1, Object key2) {
        return LongHashingUtils.hash(key1, key2);
      }
    }

    // Package-private to match D1.buckets -- available for iterator tests in the same package.
    final Hashtable.Entry[] buckets;
    private int size;

    public D2(int capacity) {
      this.buckets = Support.create(capacity);
      this.size = 0;
    }

    public int size() {
      return this.size;
    }

    public TEntry get(K1 key1, K2 key2) {
      long keyHash = D2.Entry.hash(key1, key2);
      for (TEntry te = Support.bucket(this.buckets, keyHash); te != null; te = te.next()) {
        if (te.keyHash == keyHash && te.matches(key1, key2)) {
          return te;
        }
      }
      return null;
    }

    public TEntry remove(K1 key1, K2 key2) {
      long keyHash = D2.Entry.hash(key1, key2);

      for (MutatingBucketIterator<TEntry> iter =
              Support.mutatingBucketIterator(this.buckets, keyHash);
          iter.hasNext(); ) {
        TEntry curEntry = iter.next();

        if (curEntry.matches(key1, key2)) {
          iter.remove();
          this.size -= 1;
          return curEntry;
        }
      }

      return null;
    }

    public void insert(TEntry newEntry) {
      Support.insertHeadEntry(this.buckets, newEntry.keyHash, newEntry);
      this.size += 1;
    }

    public TEntry insertOrReplace(TEntry newEntry) {
      for (MutatingBucketIterator<TEntry> iter =
              Support.mutatingBucketIterator(this.buckets, newEntry.keyHash);
          iter.hasNext(); ) {
        TEntry curEntry = iter.next();

        if (curEntry.matches(newEntry.key1, newEntry.key2)) {
          iter.replace(newEntry);
          return curEntry;
        }
      }

      Support.insertHeadEntry(this.buckets, newEntry.keyHash, newEntry);
      this.size += 1;
      return null;
    }

    /**
     * Two-key analogue of {@link D1#getOrCreate}. Computes the combined hash once and reuses it for
     * both lookup and (on miss) insert. The {@code creator} is expected to build an entry whose
     * {@code keyHash} equals {@link Entry#hash(Object, Object) D2.Entry.hash(key1, key2)}.
     */
    public TEntry getOrCreate(
        K1 key1, K2 key2, BiFunction<? super K1, ? super K2, ? extends TEntry> creator) {
      long keyHash = D2.Entry.hash(key1, key2);
      for (TEntry te = Support.bucket(this.buckets, keyHash); te != null; te = te.next()) {
        if (te.keyHash == keyHash && te.matches(key1, key2)) {
          return te;
        }
      }
      TEntry newEntry = creator.apply(key1, key2);
      Support.insertHeadEntry(this.buckets, newEntry.keyHash, newEntry);
      this.size += 1;
      return newEntry;
    }

    public void clear() {
      Support.clear(this.buckets);
      this.size = 0;
    }

    public void forEach(Consumer<? super TEntry> consumer) {
      Support.forEach(this.buckets, consumer);
    }

    /**
     * Context-passing forEach. Useful for callers that want to avoid a capturing-lambda allocation
     * -- pass a non-capturing {@link BiConsumer} (typically a {@code static final}) plus whatever
     * side-band state it needs as {@code context}.
     */
    public <T> void forEach(T context, BiConsumer<? super T, ? super TEntry> consumer) {
      Support.forEach(this.buckets, context, consumer);
    }
  }

  /**
   * Building blocks for hash-table operations.
   *
   * <p>Used by {@link D1} and {@link D2}, and available to callers that want to assemble their own
   * higher-arity table (3+ key parts) without re-implementing the bucket-array mechanics. The
   * typical recipe:
   *
   * <ul>
   *   <li>Subclass {@link Hashtable.Entry} directly, adding the key fields and a {@code
   *       matches(...)} method of your chosen arity.
   *   <li>Allocate a backing array with {@link #create(int)} or {@link #create(int, float)} (the
   *       latter scales for a target load factor; see {@link #MAX_RATIO}).
   *   <li>Use {@link #bucketIndex(Object[], long)} for the bucket lookup, {@link
   *       #bucketIterator(Hashtable.Entry[], long)} for read-only chain walks, and {@link
   *       #mutatingBucketIterator(Hashtable.Entry[], long)} when you also need {@code remove} /
   *       {@code replace}.
   *   <li>Use {@link #insertHeadEntry(Hashtable.Entry[], int, Hashtable.Entry)} to splice a new
   *       entry as the head of a bucket chain.
   *   <li>Iterate every entry with {@link #forEach(Hashtable.Entry[], Consumer)} or its
   *       context-passing sibling. For full-table sweeps with {@code remove}, use {@link
   *       #mutatingTableIterator(Hashtable.Entry[])}.
   *   <li>Clear with {@link #clear(Hashtable.Entry[])}.
   * </ul>
   *
   * <p>All bucket arrays produced by {@code create} have a power-of-two length, so {@link
   * #bucketIndex(Object[], long)} can use a bit mask.
   */
  public static final class Support {
    /**
     * Allocates a bucket array sized to hold {@code requestedSize} entries. Returned length is
     * {@code requestedSize} rounded up to the next power of two (capped at {@link #MAX_BUCKETS}).
     */
    public static final Hashtable.Entry[] create(int requestedSize) {
      return new Entry[sizeFor(requestedSize)];
    }

    /**
     * Variant of {@link #create(int)} that scales the requested working-set size before sizing the
     * bucket array. Pair with {@link #MAX_RATIO} to leave headroom over the working set for a
     * desired load factor; the canonical call is {@code create(n, MAX_RATIO)}.
     *
     * <p>The scaled size is truncated to {@code int} before going through {@link #sizeFor(int)}.
     * Truncation rather than {@code ceil} is intentional: {@code sizeFor} rounds up to the next
     * power of two anyway, so the fractional part would only matter when float fuzz pushes the
     * result across a power-of-two boundary -- {@code ceil} would then double the array size for no
     * reason (e.g. {@code 12 * 4/3 = 16.0...0005f -> ceil 17 -> sizeFor 32}).
     */
    public static final Hashtable.Entry[] create(int requestedSize, float scale) {
      return new Entry[sizeFor((int) (requestedSize * scale))];
    }

    /** Upper bound on the bucket array length returned by {@link #sizeFor(int)}. */
    static final int MAX_BUCKETS = 1 << 30;

    /**
     * Inverse of a 75% load factor. Callers that size their bucket array from a target working-set
     * size {@code n} should pass {@code create(n, MAX_RATIO)} to leave ~25% headroom in the array.
     */
    public static final float MAX_RATIO = 4.0f / 3.0f;

    /**
     * Rounds {@code requestedSize} up to the next power of two, capped at {@link #MAX_BUCKETS}.
     * Throws {@link IllegalArgumentException} for negative inputs or inputs above the cap. Returns
     * the bucket-array length to allocate.
     */
    static final int sizeFor(int requestedSize) {
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

    public static final void clear(Hashtable.Entry[] buckets) {
      Arrays.fill(buckets, null);
    }

    public static final <TEntry extends Hashtable.Entry> BucketIterator<TEntry> bucketIterator(
        Hashtable.Entry[] buckets, long keyHash) {
      return new BucketIterator<TEntry>(buckets, keyHash);
    }

    public static final <TEntry extends Hashtable.Entry>
        MutatingBucketIterator<TEntry> mutatingBucketIterator(
            Hashtable.Entry[] buckets, long keyHash) {
      return new MutatingBucketIterator<TEntry>(buckets, keyHash);
    }

    /**
     * Returns a {@link MutatingTableIterator} over every entry in {@code buckets}. Useful for
     * sweeps -- eviction, expunge -- that aren't keyed to a specific hash.
     */
    public static final <TEntry extends Hashtable.Entry>
        MutatingTableIterator<TEntry> mutatingTableIterator(Hashtable.Entry[] buckets) {
      return new MutatingTableIterator<TEntry>(buckets, 0, buckets.length);
    }

    /**
     * Variant of {@link #mutatingTableIterator(Hashtable.Entry[])} that walks only the half-open
     * bucket range {@code [startBucket, endBucket)}. Useful for resumable sweeps -- e.g. cursor-
     * based eviction in {@code AggregateTable} -- where one call drives {@code [cursor, length)}
     * and a wrap-around call drives {@code [0, cursor)}. The iterator does <b>not</b> wrap around
     * within a single instance; callers compose two iterators when wrap-around is desired. An empty
     * range ({@code startBucket == endBucket}) produces an immediately exhausted iterator.
     *
     * @param startBucket inclusive lower bound; must be in {@code [0, buckets.length]}.
     * @param endBucket exclusive upper bound; must be in {@code [startBucket, buckets.length]}.
     */
    public static final <TEntry extends Hashtable.Entry>
        MutatingTableIterator<TEntry> mutatingTableIterator(
            Hashtable.Entry[] buckets, int startBucket, int endBucket) {
      return new MutatingTableIterator<TEntry>(buckets, startBucket, endBucket);
    }

    public static final int bucketIndex(Object[] buckets, long keyHash) {
      return (int) (keyHash & buckets.length - 1);
    }

    /**
     * Splices {@code entry} in as the new head of the chain at {@code bucketIndex}. Caller is
     * responsible for size accounting -- this method only touches the chain pointers.
     */
    public static final void insertHeadEntry(
        Hashtable.Entry[] buckets, int bucketIndex, Hashtable.Entry entry) {
      entry.setNext(buckets[bucketIndex]);
      buckets[bucketIndex] = entry;
    }

    /**
     * Convenience overload of {@link #insertHeadEntry(Hashtable.Entry[], int, Hashtable.Entry)}
     * that derives the bucket index from {@code keyHash}. Use this when the caller has the hash but
     * not the index; if the index has already been computed for another reason, prefer the
     * int-taking overload to avoid the redundant mask.
     */
    public static final void insertHeadEntry(
        Hashtable.Entry[] buckets, long keyHash, Hashtable.Entry entry) {
      insertHeadEntry(buckets, bucketIndex(buckets, keyHash), entry);
    }

    /**
     * Returns the head entry of the bucket that {@code keyHash} maps to, cast to the caller's
     * concrete entry type. The unchecked cast lives here so the chain-walk loop at the call site
     * doesn't need to thread a raw {@link Entry} variable through.
     */
    @SuppressWarnings("unchecked")
    public static final <TEntry extends Hashtable.Entry> TEntry bucket(
        Hashtable.Entry[] buckets, long keyHash) {
      return (TEntry) buckets[bucketIndex(buckets, keyHash)];
    }

    /**
     * Walks every entry in {@code buckets} and invokes {@code consumer} on it. The unchecked cast
     * to {@code TEntry} lives here (mirroring {@link Entry#next()}) so callers don't have to
     * sprinkle it across their own forEach loops.
     */
    @SuppressWarnings("unchecked")
    public static final <TEntry extends Hashtable.Entry> void forEach(
        Hashtable.Entry[] buckets, Consumer<? super TEntry> consumer) {
      for (int i = 0; i < buckets.length; i++) {
        for (Hashtable.Entry e = buckets[i]; e != null; e = e.next()) {
          consumer.accept((TEntry) e);
        }
      }
    }

    /**
     * Context-passing variant of {@link #forEach(Hashtable.Entry[], Consumer)}. Pair a
     * non-capturing {@link BiConsumer} (typically a {@code static final}) with side-band state
     * passed as {@code context} to avoid a fresh-Consumer allocation each call.
     */
    @SuppressWarnings("unchecked")
    public static final <T, TEntry extends Hashtable.Entry> void forEach(
        Hashtable.Entry[] buckets, T context, BiConsumer<? super T, ? super TEntry> consumer) {
      for (int i = 0; i < buckets.length; i++) {
        for (Hashtable.Entry e = buckets[i]; e != null; e = e.next()) {
          consumer.accept(context, (TEntry) e);
        }
      }
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

    BucketIterator(Hashtable.Entry[] buckets, long keyHash) {
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

    MutatingBucketIterator(Hashtable.Entry[] buckets, long keyHash) {
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

    public void replace(TEntry replacementEntry) {
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

    void setPrevNext(Hashtable.Entry nextEntry) {
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

    MutatingTableIterator(Hashtable.Entry[] buckets, int startBucket, int endBucket) {
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
