package datadog.trace.util;

import datadog.trace.api.function.Strategy;
import datadog.trace.api.function.StrategyConsumer;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Open-addressed, single-array find-or-create over <b>self-contained entries</b> — each slot is one
 * reference to an entry that carries its own key (and, typically, a cached hash). One array, one
 * reference per slot: entry publication is a single reference store, so a reader sees {@code null}
 * or a complete entry (never a torn one), and {@code final} identity fields on the entry are
 * visible under racy publication. That sidesteps the memory-ordering / visibility problems parallel
 * key/hash/value arrays would create — no {@code volatile}, no atomics.
 *
 * <p><b>Concurrent use is racy by design, not lock-free-safe in general.</b> The single-reference
 * guarantee above covers only the slot reference and an entry's {@code final} fields; a non-final
 * payload field written after construction is <b>not</b> safely published by a racing {@link
 * #tryGetOrCreate}, and a freshly built entry that loses the slot race is discarded without ever
 * being retained by the table. That is fine for build-then-publish usage (populate on one thread,
 * e.g. a static-final table, then read from many) and for a payload where a stale/default read or a
 * discarded race-loser is <b>benign</b> (miss → recreate; clobber → one wins). For concurrent
 * <i>creation</i> of entries with meaningful post-construction state, keep entry state fully {@code
 * final} — do not rely on this class for safe publication of mutable entry fields.
 *
 * <p><b>Bounded by construction</b> — and that is a feature, not just a limit. {@link #create}
 * takes a cardinality budget, so you cannot build one without deciding <i>how big it may get</i> —
 * the question whose unasked version becomes an unbounded-growth leak in a long-lived agent living
 * in someone else's process. A regular {@code Map}'s auto-resize lets you forget that (fine when
 * you own the heap; the wrong default when you are a guest in one). This table never grows on its
 * own: {@link #get} / {@link #tryGetOrCreate} / {@link #insert} <i>cap</i> rather than churn — a
 * full table degrades to recompute-on-miss with bounded memory and no reallocation — and growth is
 * an explicit, deliberate {@link #resize} / {@link #resizingInsert}. So it defaults to the
 * bounded-footprint posture the agent needs, with unbounded growth an opt-in you have to reach for
 * (and one that, over externally-controlled keys, is the leak this structure otherwise prevents —
 * see {@link #resizingInsert}). The trade only pays when a miss is benign (a cache / interner), not
 * for a must-hold-everything map.
 *
 * <h2>Choosing between the three tables</h2>
 *
 * <ol>
 *   <li><b>Concurrent access?</b> Use {@code ConcurrentHashtable} -- the only thread-safe one of
 *       the three. This class is racy by design (see above), and {@code Hashtable} is not
 *       thread-safe at all.
 *   <li><b>Otherwise: does the population reset wholesale, or evolve?</b> A table cleared as a unit
 *       -- once per cycle, per request, or built and then discarded -- wants this class, whose open
 *       addressing has no tombstones and so offers no removal beyond clearing. A table whose
 *       entries come and go independently wants the chained {@code Hashtable}, which removes and
 *       evicts in place.
 * </ol>
 *
 * <p>Lifetime is the usual shorthand for that second question and mostly works, because a
 * short-lived table never needs to remove -- it just dies. The case it mis-sorts is a long-lived
 * table that resets on a cycle: that is a sequence of short lives, and belongs with the short-lived
 * ones. Compare a table that evicts stale entries one at a time while the busy ones survive the
 * cycle (evolving -- {@code Hashtable}) against one that clears every entry each time it reports
 * (resets -- this class).
 *
 * <p><b>Strategy roles, split by concern.</b> The per-use policy is a small set of {@link Strategy
 * strategy} objects rather than one, so a caller supplies only what an operation needs:
 *
 * <ul>
 *   <li>a {@link MatchingStrategy} — the <i>key side</i>: {@link MatchingStrategy#hashKey hash a
 *       lookup key} (defaults to {@code hashCode}) and {@link MatchingStrategy#matches match} it
 *       against a stored entry. Used by {@link #get} / {@link #tryGetOrCreate}.
 *   <li>a {@link HashStrategy} — the <i>entry side</i>: {@link HashStrategy#hashOf hash a stored
 *       entry}. Used by {@link #insert} / {@link #iterator} / {@link #resize} (which have an entry,
 *       not a key). For {@link Entry}-based tables this is just the cached {@link Entry#hash}, so
 *       those get dedicated overloads that need no strategy at all.
 *   <li>an {@link EntryStrategy} — both of the above, for a user that does lookups <i>and</i>
 *       inserts; extend this one abstract class and you have the whole policy.
 *   <li>a {@link CreateStrategy} — how to mint an entry for a key. Cold (once per key, at warmup),
 *       so it is a {@link FunctionalInterface} you can supply as a <i>non-capturing</i> lambda.
 * </ul>
 *
 * <pre>{@code
 * private static final MyStrategy S = new MyStrategy();          // concrete type => exact type pinned
 * ...
 * E e = FlatHashtable.tryGetOrCreate(table, key, S, MyEntry::new);  // non-capturing create
 * }</pre>
 *
 * <p><b>Contract:</b> {@code table.length} must be a power of two ({@link #capacityFor}). Both
 * {@link MatchingStrategy#hashKey} and {@link HashStrategy#hashOf} may return a plain {@code
 * hashCode} — the table owns the spread ({@link #home}) — but they must be <b>consistent</b>:
 * {@code hashKey(key)} must equal {@code hashOf(entry)} for that key's entry, so a lookup lands
 * where the entry was placed (trivially true when both default to {@code hashCode}). Cardinality
 * cap / overflow / a live-size counter are <b>caller policy</b> (this class is pure mechanism): a
 * capped caller does {@link #get} first, and only on a miss checks its budget before {@link
 * #tryGetOrCreate} (so hits stay a single probe and the create path is warmup-rare).
 */
public final class FlatHashtable {
  private FlatHashtable() {}

  /**
   * Optional structure-free entry base carrying only a cached {@code hash} — an
   * <i>optimization</i>, not plumbing (open addressing needs no {@code next}), so extending it is
   * never required: bring any entry type and supply a {@link HashStrategy} yourself instead. Caller
   * contract: {@code hash} must equal the table's {@link MatchingStrategy#hashKey} for this entry's
   * key (the <i>raw</i> hash — the table applies its own spread), so the entry lands where {@link
   * #get} looks.
   */
  public abstract static class Entry {
    public final long hash;

    protected Entry(long hash) {
      this.hash = hash;
    }
  }

  /**
   * Single-key, {@code HashMap}-style convenience over the {@linkplain FlatHashtable static core}:
   * {@link #get} / {@link #tryGetOrCreate} / {@link #insert} / {@link #forEach} without writing a
   * {@link MatchingStrategy}. Reach for it when you want something quick that beats {@code
   * HashMap<K, V>} — the entry carries its own value fields, so updating an existing value is
   * allocation-free (look up once, then write the returned entry).
   *
   * <p><b>Fixed or growable, chosen at construction.</b> {@link #createFixed} keeps the raw core's
   * bounded posture — the table holds up to {@code maxCapacity} entries, then {@link
   * #tryGetOrCreate} caps and returns {@code null} (the caller supplies the overflow default).
   * {@link #createGrowable} trades that for {@code HashMap}-like ergonomics — its {@code
   * initialCapacity} is a sizing hint, not a cap, the table doubles when it fills past its load
   * factor, and {@code tryGetOrCreate} never returns {@code null}. The distinct factory names make
   * the choice explicit at the call site (there's no ambiguous {@code (Class, int)} constructor);
   * {@code Capacity} always counts <i>entries</i>, matching the chained {@code
   * Hashtable.D1.createCapped}. Across the family a table factory's number is always entries — only
   * the low-level array allocators take a bucket count.
   *
   * <p><b>Entry-centric, not strategy-based.</b> Supply a {@link D1.Entry} subclass carrying the
   * key and value fields; key equality is {@link Object#equals} by default (override {@link
   * Entry#matches(Object)} for e.g. reference equality on interned keys). The strategy-based core
   * is untouched and remains the expert path.
   *
   * <p><b>No {@code remove}.</b> The open-addressed core has no tombstones (an empty slot
   * terminates a probe), so deletion isn't supported — this layer is for accumulate / interner /
   * counter workloads, not churn.
   *
   * <p><b>Not thread-safe.</b>
   *
   * @param <K> the key type
   * @param <TEntry> the user's {@link D1.Entry D1.Entry&lt;K&gt;} subclass
   */
  public static final class D1<K, TEntry extends D1.Entry<K>> {
    /**
     * Abstract base for {@link D1} entries. Subclass to add value fields you wish to mutate in
     * place after retrieving the entry via {@link D1#get}. The key is captured at construction and
     * stored alongside its precomputed hash (via {@link FlatHashtable.Entry}).
     *
     * @param <K> the key type
     */
    public abstract static class Entry<K> extends FlatHashtable.Entry {
      final K key;

      protected Entry(@Nullable K key) {
        super(hash(key));
        this.key = key;
      }

      @Nullable
      public final K key() {
        return key;
      }

      public boolean matches(@Nullable Object key) {
        return Objects.equals(this.key, key);
      }

      /**
       * Returns the lookup hash for {@code key}. Null keys map to {@link Long#MIN_VALUE} so they
       * don't collide with a real key that hashes to 0; real-key collisions are resolved by {@link
       * #matches(Object)}.
       */
      public static long hash(@Nullable Object key) {
        return (key == null) ? Long.MIN_VALUE : key.hashCode();
      }
    }

    private final boolean growable;
    private final float loadFactor;
    private TEntry[] table;
    private int size;
    private int limit; // grow trigger when growable; hard cap when fixed

    private D1(TEntry[] table, float loadFactor, boolean growable, int limit) {
      this.growable = growable;
      this.loadFactor = loadFactor;
      this.table = table;
      this.size = 0;
      this.limit = limit;
    }

    /**
     * A bounded {@link D1} holding up to {@code maxCapacity} entries at the {@link
     * #DEFAULT_LOAD_FACTOR}, then capping ({@link #tryGetOrCreate} returns {@code null}).
     */
    @Nonnull
    public static <K, E extends D1.Entry<K>> D1<K, E> createFixed(
        @Nonnull Class<E> type, int maxCapacity) {
      return createFixed(type, maxCapacity, DEFAULT_LOAD_FACTOR);
    }

    @Nonnull
    public static <K, E extends D1.Entry<K>> D1<K, E> createFixed(
        @Nonnull Class<E> type, int maxCapacity, float loadFactor) {
      return new D1<>(create(type, maxCapacity, loadFactor), loadFactor, false, maxCapacity);
    }

    /**
     * A growable {@link D1} sized initially for {@code initialCapacity} entries at the {@link
     * #DEFAULT_LOAD_FACTOR}; doubles on demand, so it never caps.
     */
    @Nonnull
    public static <K, E extends D1.Entry<K>> D1<K, E> createGrowable(
        @Nonnull Class<E> type, int initialCapacity) {
      return createGrowable(type, initialCapacity, DEFAULT_LOAD_FACTOR);
    }

    @Nonnull
    public static <K, E extends D1.Entry<K>> D1<K, E> createGrowable(
        @Nonnull Class<E> type, int initialCapacity, float loadFactor) {
      E[] table = create(type, initialCapacity, loadFactor);
      return new D1<>(table, loadFactor, true, (int) (table.length * loadFactor));
    }

    public int size() {
      return size;
    }

    /** Existing entry for {@code key}, or {@code null}. Read-only — never creates. */
    @Nullable
    public TEntry get(@Nullable K key) {
      final long keyHash = Entry.hash(key);
      final TEntry[] table = this.table;
      final int mask = table.length - 1;
      final int start = home(keyHash, mask);
      int i = start;
      for (; ; ) {
        final TEntry e = table[i];
        if (e == null) {
          return null; // empty slot terminates the probe (no tombstones)
        }
        if (e.hash == keyHash && e.matches(key)) {
          return e;
        }
        i = (i + 1) & mask;
        if (i == start) {
          return null; // wrapped ⇒ absent (can't happen once growth keeps a free slot)
        }
      }
    }

    /**
     * Existing entry for {@code key}, or a freshly {@link CreateStrategy#create created} + inserted
     * one. A growable table never returns {@code null}; a fixed one returns {@code null} when full
     * and {@code key} is absent (the caller supplies the overflow default). A hit is always
     * returned even at capacity — the cap blocks only creation, not lookup.
     *
     * <p>The {@code try} prefix marks "this may refuse" — a growable table simply never exercises
     * it. The name has to serve both postures, since the posture is chosen per instance at the
     * factory while the method name is per class, and the two mistakes are not symmetric:
     * under-promising refusal costs an NPE at the cap, over-promising it costs a redundant null
     * check. So it errs toward {@code try}.
     */
    @Nullable
    public TEntry tryGetOrCreate(@Nullable K key, @Nonnull CreateStrategy<TEntry, K> createStrat) {
      final TEntry existing = get(key);
      if (existing != null) {
        return existing;
      }
      if (size >= limit) {
        if (!growable) {
          return null; // fixed table full ⇒ caller supplies the overflow default
        }
        grow();
      }
      final TEntry created = createStrat.create(key);
      FlatHashtable.insert(table, created); // fits: cap/growth keeps a free slot
      size++;
      return created;
    }

    /**
     * Unconditionally adds {@code entry} ({@code true}), or {@code false} if a fixed table is full.
     * Comparison-free and caller-responsible (same contract as {@link FlatHashtable#insert}): the
     * caller must ensure {@code entry}'s key is absent, else it lands shadowed.
     */
    public boolean insert(@Nonnull TEntry entry) {
      if (size >= limit) {
        if (!growable) {
          return false; // fixed table full
        }
        grow();
      }
      FlatHashtable.insert(table, entry);
      size++;
      return true;
    }

    private void grow() {
      table = resize(table);
      limit = (int) (table.length * loadFactor);
    }

    public void clear() {
      Arrays.fill(table, null);
      size = 0;
    }

    public void forEach(@Nonnull Consumer<? super TEntry> consumer) {
      FlatHashtable.forEach(table, consumer);
    }

    /**
     * Context-passing {@link #forEach(Consumer)}: pair a non-capturing {@link BiConsumer} with
     * side-band {@code context} to avoid a per-call closure.
     */
    public <C> void forEach(C context, @Nonnull BiConsumer<? super C, ? super TEntry> consumer) {
      FlatHashtable.forEach(table, context, consumer);
    }
  }

  /**
   * Two-key (composite-key) analogue of {@link D1}. Both key parts pass directly through {@link
   * #get} / {@link #tryGetOrCreate}, so a lookup allocates no {@code Pair} — the win over {@code
   * HashMap<Pair, V>}. Same fixed-or-growable ({@link #createFixed} / {@link #createGrowable}),
   * entry-centric, no-{@code remove}, not-thread-safe contract as {@link D1}.
   *
   * @param <K1> first key type
   * @param <K2> second key type
   * @param <TEntry> the user's {@link D2.Entry D2.Entry&lt;K1, K2&gt;} subclass
   */
  public static final class D2<K1, K2, TEntry extends D2.Entry<K1, K2>> {
    /**
     * Abstract base for {@link D2} entries. Subclass to add value fields you wish to mutate in
     * place. Both key parts are captured at construction alongside their combined hash.
     *
     * @param <K1> first key type
     * @param <K2> second key type
     */
    public abstract static class Entry<K1, K2> extends FlatHashtable.Entry {
      final K1 key1;
      final K2 key2;

      protected Entry(@Nullable K1 key1, @Nullable K2 key2) {
        super(hash(key1, key2));
        this.key1 = key1;
        this.key2 = key2;
      }

      @Nullable
      public final K1 key1() {
        return key1;
      }

      @Nullable
      public final K2 key2() {
        return key2;
      }

      public boolean matches(@Nullable K1 key1, @Nullable K2 key2) {
        return Objects.equals(this.key1, key1) && Objects.equals(this.key2, key2);
      }

      /**
       * Returns the combined lookup hash for both key parts via {@link
       * LongHashingUtils#hash(Object, Object)}. Null parts contribute {@code 0} (not a sentinel,
       * unlike {@link D1.Entry}); {@link #matches(Object, Object)} resolves any collision.
       */
      public static long hash(@Nullable Object key1, @Nullable Object key2) {
        return LongHashingUtils.hash(key1, key2);
      }
    }

    private final boolean growable;
    private final float loadFactor;
    private TEntry[] table;
    private int size;
    private int limit; // grow trigger when growable; hard cap when fixed

    private D2(TEntry[] table, float loadFactor, boolean growable, int limit) {
      this.growable = growable;
      this.loadFactor = loadFactor;
      this.table = table;
      this.size = 0;
      this.limit = limit;
    }

    /**
     * A bounded {@link D2} holding up to {@code maxCapacity} entries at the {@link
     * #DEFAULT_LOAD_FACTOR}, then capping ({@link #tryGetOrCreate} returns {@code null}).
     */
    @Nonnull
    public static <K1, K2, E extends D2.Entry<K1, K2>> D2<K1, K2, E> createFixed(
        @Nonnull Class<E> type, int maxCapacity) {
      return createFixed(type, maxCapacity, DEFAULT_LOAD_FACTOR);
    }

    @Nonnull
    public static <K1, K2, E extends D2.Entry<K1, K2>> D2<K1, K2, E> createFixed(
        @Nonnull Class<E> type, int maxCapacity, float loadFactor) {
      return new D2<>(create(type, maxCapacity, loadFactor), loadFactor, false, maxCapacity);
    }

    /**
     * A growable {@link D2} sized initially for {@code initialCapacity} entries at the {@link
     * #DEFAULT_LOAD_FACTOR}; doubles on demand, so it never caps.
     */
    @Nonnull
    public static <K1, K2, E extends D2.Entry<K1, K2>> D2<K1, K2, E> createGrowable(
        @Nonnull Class<E> type, int initialCapacity) {
      return createGrowable(type, initialCapacity, DEFAULT_LOAD_FACTOR);
    }

    @Nonnull
    public static <K1, K2, E extends D2.Entry<K1, K2>> D2<K1, K2, E> createGrowable(
        @Nonnull Class<E> type, int initialCapacity, float loadFactor) {
      E[] table = create(type, initialCapacity, loadFactor);
      return new D2<>(table, loadFactor, true, (int) (table.length * loadFactor));
    }

    public int size() {
      return size;
    }

    /** Existing entry for {@code (key1, key2)}, or {@code null}. Read-only — never creates. */
    @Nullable
    public TEntry get(@Nullable K1 key1, @Nullable K2 key2) {
      final long keyHash = Entry.hash(key1, key2);
      final TEntry[] table = this.table;
      final int mask = table.length - 1;
      final int start = home(keyHash, mask);
      int i = start;
      for (; ; ) {
        final TEntry e = table[i];
        if (e == null) {
          return null;
        }
        if (e.hash == keyHash && e.matches(key1, key2)) {
          return e;
        }
        i = (i + 1) & mask;
        if (i == start) {
          return null;
        }
      }
    }

    /**
     * Two-key analogue of {@link D1#tryGetOrCreate}: growable never returns {@code null}; fixed
     * returns {@code null} when full and {@code (key1, key2)} is absent.
     */
    @Nullable
    public TEntry tryGetOrCreate(
        @Nullable K1 key1,
        @Nullable K2 key2,
        @Nonnull CreateStrategy2<TEntry, K1, K2> createStrat) {
      final TEntry existing = get(key1, key2);
      if (existing != null) {
        return existing;
      }
      if (size >= limit) {
        if (!growable) {
          return null; // fixed table full ⇒ caller supplies the overflow default
        }
        grow();
      }
      final TEntry created = createStrat.create(key1, key2);
      FlatHashtable.insert(table, created);
      size++;
      return created;
    }

    /**
     * Unconditionally adds {@code entry} ({@code true}), or {@code false} if a fixed table is full.
     * Comparison-free and caller-responsible: the caller must ensure {@code (key1, key2)} is
     * absent.
     */
    public boolean insert(@Nonnull TEntry entry) {
      if (size >= limit) {
        if (!growable) {
          return false; // fixed table full
        }
        grow();
      }
      FlatHashtable.insert(table, entry);
      size++;
      return true;
    }

    private void grow() {
      table = resize(table);
      limit = (int) (table.length * loadFactor);
    }

    public void clear() {
      Arrays.fill(table, null);
      size = 0;
    }

    public void forEach(@Nonnull Consumer<? super TEntry> consumer) {
      FlatHashtable.forEach(table, consumer);
    }

    public <C> void forEach(C context, @Nonnull BiConsumer<? super C, ? super TEntry> consumer) {
      FlatHashtable.forEach(table, context, consumer);
    }
  }

  /**
   * Two-key creation strategy for {@link D2#tryGetOrCreate}: mint a new entry for {@code (key1,
   * key2)}. Like {@link CreateStrategy}, supply a {@code static final} constant or a
   * <i>non-capturing</i> lambda (e.g. {@code MyEntry::new}) so it stays a single monomorphic,
   * allocation-free instance.
   *
   * @param <E> stored entry to create
   * @param <K1> first key type
   * @param <K2> second key type
   */
  @Strategy
  @FunctionalInterface
  public interface CreateStrategy2<E, K1, K2> {
    @Nonnull
    E create(@Nullable K1 key1, @Nullable K2 key2);
  }

  /**
   * Entry-side strategy: the hash of a stored {@code entry} — its home is {@link #home}{@code
   * (hashOf(entry))}. Used by {@link #insert} / {@link #iterator} / {@link #resize}, which have an
   * entry rather than a lookup key. Must be consistent with {@link MatchingStrategy#hashKey} (see
   * the class contract). Supply a {@code static final} constant or a non-capturing lambda so it
   * stays a single monomorphic instance (see {@link Strategy}); for {@link Entry}-based tables use
   * the strategy-free overloads, which read {@link Entry#hash} directly.
   *
   * @param <E> stored entry
   */
  @Strategy
  @FunctionalInterface
  public interface HashStrategy<E> {
    long hashOf(@Nonnull E entry);
  }

  /**
   * Key-side strategy: whether a stored {@code entry} is the one for a lookup {@code key} ({@link
   * #matches}), and how to hash that key ({@link #hashKey}). {@code hashKey} <b>defaults to {@code
   * key.hashCode()}</b> — override it only when the key's identity needs different hashing (e.g.
   * case-insensitive), and then keep it consistent with the table's {@link HashStrategy#hashOf}.
   * Used by {@link #get} / {@link #tryGetOrCreate}.
   *
   * <p>A {@link FunctionalInterface} ({@code matches} is the sole abstract method), so the common
   * case can be a non-capturing lambda; a strategy that also customizes hashing is a named class
   * that overrides {@code hashKey}.
   *
   * @param <E> stored entry
   * @param <K> lookup key
   */
  @Strategy
  @FunctionalInterface
  public interface MatchingStrategy<E, K> {
    /** Whether the stored {@code entry} is the one for {@code key}. */
    boolean matches(@Nonnull E entry, K key);

    /**
     * Hash of {@code key}; defaults to {@code key.hashCode()} (the table applies its own spread).
     * This default NPEs on a {@code null} key — unlike {@link D1.Entry}, which maps a {@code null}
     * key to a dedicated hash sentinel, the raw core takes no position on {@code null} keys; a
     * strategy that must support them overrides {@code hashKey} (and {@code matches}) to handle
     * {@code null} itself.
     */
    default long hashKey(K key) {
      return key.hashCode();
    }
  }

  /**
   * The whole policy for a table you both look up in and insert into: a {@link MatchingStrategy}
   * (key side) and a {@link HashStrategy} (entry side) in one. Extend as a <b>stateless</b> final
   * class held in a concrete-typed {@code static final} singleton so the JIT specializes each call
   * site (see {@link Strategy}); an abstract <b>class</b> (not an interface) so a specialization
   * miss falls back to {@code invokevirtual} rather than the costlier megamorphic {@code
   * invokeinterface}.
   *
   * @param <E> stored entry
   * @param <K> lookup key
   */
  @Strategy
  public abstract static class EntryStrategy<E, K>
      implements HashStrategy<E>, MatchingStrategy<E, K> {}

  /**
   * {@link EntryStrategy} for {@code String} keys compared case-insensitively: seals {@link
   * #hashKey} to {@link Strings#caseInsensitiveHashCode} (consistent with {@link
   * String#equalsIgnoreCase}, which callers use in {@link #matches}). Callers still supply {@link
   * #matches} (typically {@code key.equalsIgnoreCase(entry.key)}) and {@link #hashOf} (the same
   * case-insensitive hash of the entry's key, or the cached {@link Entry#hash}).
   *
   * @param <E> stored entry — self-contained (carries its own key)
   */
  public abstract static class CaseInsensitiveStringStrategy<E> extends EntryStrategy<E, String> {
    @Override
    public final long hashKey(String key) {
      return Strings.caseInsensitiveHashCode(key); // raw; the table spreads before masking
    }
  }

  /**
   * Creation strategy: mint a new entry for {@code key} (called once, on insert). A {@link
   * FunctionalInterface} — supply a {@code static final} constant or a <i>non-capturing</i> lambda
   * (e.g. {@code MyEntry::new}) so it stays a single monomorphic, allocation-free instance; a
   * capturing lambda silently re-allocates per call and can de-monomorphize the site (see {@link
   * Strategy}). Bespoke rather than {@link java.util.function.Function} so it carries the {@link
   * Strategy} contract and reads as {@code create} at the call site.
   *
   * @param <E> stored entry to create
   * @param <K> lookup key
   */
  @Strategy
  @FunctionalInterface
  public interface CreateStrategy<E, K> {
    @Nonnull
    E create(@Nullable K key);
  }

  /**
   * Balanced default load factor — target fill {@code <= 0.5} ({@code >= 2x} capacity). Linear
   * probing then costs ~1.5 probes on a hit, ~2.5 on a miss (Knuth); the general-purpose sweet
   * spot.
   */
  public static final float DEFAULT_LOAD_FACTOR = 0.5f;

  /**
   * Sparse load factor — target fill {@code <= 0.25} ({@code >= 4x} capacity): ~1.2 probes on a
   * hit, ~1.4 on a miss. For miss-heavy hot paths (membership checks) where the extra empty slots
   * are cheap and shaving the (quadratic-in-load) miss cost is worth the memory. Measure before
   * preferring it to {@link #DEFAULT_LOAD_FACTOR}. There is deliberately no higher-than-default
   * constant — open addressing degrades sharply past 0.5 (~8.5 probes/miss at 0.75).
   */
  public static final float LOW_LOAD_FACTOR = 0.25f;

  /** Power-of-two capacity for a cardinality budget at the {@link #DEFAULT_LOAD_FACTOR}. */
  public static int capacityFor(int cardinalityLimit) {
    return capacityFor(cardinalityLimit, DEFAULT_LOAD_FACTOR);
  }

  /**
   * Power-of-two capacity for a cardinality budget at {@code loadFactor}: the smallest power of two
   * {@code >= ceil(cardinalityLimit / loadFactor)}. Because it rounds up to a power of two, the
   * achieved fill is often below {@code loadFactor} (never above) — you always get at least the
   * headroom you asked for.
   */
  public static int capacityFor(int cardinalityLimit, float loadFactor) {
    if (cardinalityLimit <= 0) {
      throw new IllegalArgumentException("cardinalityLimit must be positive: " + cardinalityLimit);
    }
    if (!(loadFactor > 0f && loadFactor < 1f)) {
      throw new IllegalArgumentException("loadFactor must be in (0, 1): " + loadFactor);
    }
    int min = (int) Math.ceil(cardinalityLimit / (double) loadFactor);
    int capacity = Integer.highestOneBit(min - 1) << 1;
    if (capacity <= 0) {
      throw new IllegalArgumentException(
          "cardinalityLimit "
              + cardinalityLimit
              + " at loadFactor "
              + loadFactor
              + " requires a capacity larger than Integer.MAX_VALUE");
    }
    return capacity;
  }

  /**
   * Allocates a correctly-typed table for a cardinality budget ({@link #capacityFor} slots).
   * Passing {@code type} makes the array's runtime component type {@code E} rather than {@code
   * Object[]} — typed reads, real array-store checks, and a monomorphic element type for the JIT.
   * Callers can't {@code new E[]} themselves under erasure; this does the one reflective allocation
   * at construction (off any hot path). Note: this {@code create} mints the backing array; {@link
   * CreateStrategy#create} mints an entry — different types, no ambiguity at the call site.
   */
  @SuppressWarnings("unchecked")
  @Nonnull
  public static <E> E[] create(@Nonnull Class<E> type, int cardinalityLimit) {
    return (E[]) Array.newInstance(type, capacityFor(cardinalityLimit));
  }

  /**
   * {@link #create(Class, int)} at an explicit {@code loadFactor} (see {@link #capacityFor(int,
   * float)} and the {@link #DEFAULT_LOAD_FACTOR} / {@link #LOW_LOAD_FACTOR} constants).
   */
  @SuppressWarnings("unchecked")
  @Nonnull
  public static <E> E[] create(@Nonnull Class<E> type, int cardinalityLimit, float loadFactor) {
    return (E[]) Array.newInstance(type, capacityFor(cardinalityLimit, loadFactor));
  }

  /**
   * Existing entry for {@code key}, or {@code null}. Read-only — never creates. Single probe on a
   * hit; walks to the first empty slot (or all the way around) on a miss.
   */
  @StrategyConsumer
  @Nullable
  public static <E, K> E get(
      @Nonnull E[] table, K key, @Nonnull MatchingStrategy<E, K> matchStrat) {
    final int mask = table.length - 1;
    final int start = home(matchStrat.hashKey(key), mask);
    int i = start;
    for (; ; ) {
      final E e = table[i];
      if (e == null) {
        return null; // empty slot terminates the probe (no tombstones)
      }
      if (matchStrat.matches(e, key)) {
        return e;
      }
      i = (i + 1) & mask;
      if (i == start) {
        return null; // wrapped ⇒ full, absent
      }
    }
  }

  /**
   * Existing entry for {@code key}, or a freshly {@link CreateStrategy#create created} + inserted
   * one. Returns {@code null} only if the table is full (no empty slot) — the caller supplies its
   * overflow default. The insert is a single plain reference store: a concurrent clobber /
   * double-create is acceptable only when the payload makes it benign (see class doc).
   */
  @StrategyConsumer
  @Nullable
  public static <E, K> E tryGetOrCreate(
      @Nonnull E[] table,
      K key,
      @Nonnull MatchingStrategy<E, K> matchStrat,
      @Nonnull CreateStrategy<E, K> createStrat) {
    final int mask = table.length - 1;
    final int start = home(matchStrat.hashKey(key), mask);
    int i = start;
    for (; ; ) {
      final E e = table[i];
      if (e == null) {
        final E created = createStrat.create(key);
        table[i] = created; // single-reference publish; benign clobber (see class doc)
        return created;
      }
      if (matchStrat.matches(e, key)) {
        return e;
      }
      i = (i + 1) & mask;
      if (i == start) {
        return null; // wrapped ⇒ full
      }
    }
  }

  /**
   * Unconditionally adds {@code entry} at the first empty slot from its {@link Entry#hash home};
   * {@code false} if the table is full. Convenience over the {@link HashStrategy}-taking overload
   * for {@link Entry}-based entries (the home comes from the entry, so no strategy is needed).
   *
   * <p><b>Comparison-free and caller-responsible.</b> It does not check for an existing key, so the
   * caller must ensure {@code entry}'s key is absent. A duplicate lands <i>shadowed</i> further
   * along the probe run — unreachable by {@link #get}, wasting a slot, and (if the key is later
   * removed) able to resurrect stale data. Reach for it only from the expert tier, with that
   * contract in hand.
   */
  public static <E extends Entry> boolean insert(@Nonnull E[] table, @Nonnull E entry) {
    return placeAt(table, entry, entry.hash);
  }

  /**
   * {@link #insert(Entry[], Entry)} for any entry type: the home comes from {@link
   * HashStrategy#hashOf}. Same comparison-free, caller-ensures-absence contract.
   */
  @StrategyConsumer
  public static <E> boolean insert(
      @Nonnull E[] table, @Nonnull E entry, @Nonnull HashStrategy<E> hashStrat) {
    return placeAt(table, entry, hashStrat.hashOf(entry));
  }

  /**
   * Shared placement core: probe from {@code hash}'s home to the first empty slot; false if full.
   */
  private static <E> boolean placeAt(E[] table, E entry, long hash) {
    final int mask = table.length - 1;
    final int start = home(hash, mask);
    int i = start;
    for (; ; ) {
      if (table[i] == null) {
        table[i] = entry; // single-reference publish (see class doc)
        return true;
      }
      i = (i + 1) & mask;
      if (i == start) {
        return false; // wrapped ⇒ full
      }
    }
  }

  /**
   * Placement slot for {@code hash} in a table of {@code mask + 1} slots. The table owns the
   * spread: a golden-ratio (Fibonacci) multiply diffuses the hash across all bits — robust to weak
   * or {@code int}-derived {@code hashCode}s and to full 64-bit composite hashes alike — then the
   * low index bits are taken. So a strategy may return a plain {@code hashCode} without pre-mixing.
   * Package-private so tests can predict slots.
   */
  static int home(long hash, int mask) {
    long z = hash * 0x9E3779B97F4A7C15L; // 2^64 / golden ratio; odd ⇒ a bijection (loses no bits)
    z ^= z >>> 32; // fold the well-mixed high half down into the low bits the mask keeps
    return (int) z & mask;
  }

  /**
   * Doubles capacity and rehashes every entry into a new table — call when {@link #insert} returns
   * {@code false} and you want to grow rather than reject; the caller stores the returned array
   * back. Convenience over the {@link HashStrategy}-taking overload for {@link Entry}-based entries
   * (the home comes from {@link Entry#hash}). See {@link #resizingInsert(Entry[], Entry)} to do
   * both in one call, and its note on growing over unbounded key domains.
   */
  @Nonnull
  public static <E extends Entry> E[] resize(@Nonnull E[] table) {
    E[] grown = allocateGrown(table);
    for (final E e : table) {
      if (e != null) {
        placeAt(grown, e, e.hash);
      }
    }
    return grown;
  }

  /**
   * {@link #resize(Entry[])} for any entry type: each entry's home comes from {@link
   * HashStrategy#hashOf}. Not a {@link StrategyConsumer} — the rehash is a cold, one-off traversal,
   * not a hot specialization site.
   */
  @Nonnull
  public static <E> E[] resize(@Nonnull E[] table, @Nonnull HashStrategy<E> hashStrat) {
    E[] grown = allocateGrown(table);
    for (final E e : table) {
      if (e != null) {
        placeAt(grown, e, hashStrat.hashOf(e));
      }
    }
    return grown;
  }

  /**
   * A new, empty table of twice the capacity, of the same runtime component type as {@code table}.
   */
  @SuppressWarnings("unchecked")
  private static <E> E[] allocateGrown(E[] table) {
    return (E[]) Array.newInstance(table.getClass().getComponentType(), grownLength(table.length));
  }

  /**
   * {@code currentLength << 1}, guarded against overflow. {@code currentLength << 1} overflows to a
   * negative value at {@code currentLength == 1 << 30}; without this check the failure surfaces as
   * an opaque {@code NegativeArraySizeException} from {@code Array.newInstance} in {@link
   * #allocateGrown}. Same overflow class as {@link #capacityFor}'s guard -- this is the growth-time
   * counterpart.
   */
  static int grownLength(int currentLength) {
    int grownLength = currentLength << 1;
    if (grownLength <= 0) {
      throw new IllegalStateException(
          "cannot grow a table of length " + currentLength + " past Integer.MAX_VALUE");
    }
    return grownLength;
  }

  /**
   * {@link #insert(Entry[], Entry) insert} that grows on demand: adds {@code entry}, {@link
   * #resize(Entry[]) resizing} first if the table is full, and returns the table to store back —
   * the <b>same</b> array if it fit, a <b>new larger</b> one if it grew:
   *
   * <pre>{@code
   * table = FlatHashtable.resizingInsert(table, entry); // always reassign
   * }</pre>
   *
   * Same comparison-free, caller-ensures-absence contract as {@link #insert}.
   *
   * <p><b>Grows unboundedly.</b> Unlike {@code insert}'s {@code false}, this hides the full signal,
   * so it is the easiest place to leak memory: use it only for a genuinely bounded key domain,
   * never over externally-controlled cardinality.
   *
   * <p><b>Grows at 100% fill, not at a load factor.</b> The raw core carries no live-size counter
   * (see the class contract), so it has no cheap way to know the current fill short of a full table
   * scan; growing only when {@link #insert} reports "full" is the only trigger available without
   * one. {@link #DEFAULT_LOAD_FACTOR} and {@link #LOW_LOAD_FACTOR}'s Knuth figures size a
   * <i>fixed</i> table (via {@link #capacityFor} / {@link #create}) up front for its expected
   * cardinality; they are not an insert-time growth trigger here. A caller that sizes up front —
   * the expected usage — never sees the difference; one that instead leans on {@code
   * resizingInsert} to grow an under-sized table from scratch runs fill up through 0.75, 0.9, and
   * 1.0 before doubling, past where those figures stop applying. A caller that already tracks live
   * size (as {@link D1} / {@link D2} do) can grow at a load factor of its own choosing instead.
   */
  @Nonnull
  public static <E extends Entry> E[] resizingInsert(@Nonnull E[] table, @Nonnull E entry) {
    E[] t = table;
    while (!insert(t, entry)) {
      t = resize(t); // one doubling always suffices; the loop is belt-and-braces
    }
    return t;
  }

  /**
   * {@link #resizingInsert(Entry[], Entry)} for any entry type (home via {@link
   * HashStrategy#hashOf}). Same grows-unboundedly caution and same probe-to-full growth trigger.
   */
  @StrategyConsumer
  @Nonnull
  public static <E> E[] resizingInsert(
      @Nonnull E[] table, @Nonnull E entry, @Nonnull HashStrategy<E> hashStrat) {
    E[] t = table;
    while (!insert(t, entry, hashStrat)) {
      t = resize(t, hashStrat);
    }
    return t;
  }

  /** Applies {@code consumer} to every entry in {@code table} (skipping empty slots); any order. */
  public static <E> void forEach(@Nonnull E[] table, @Nonnull Consumer<? super E> consumer) {
    for (final E e : table) {
      if (e != null) {
        consumer.accept(e);
      }
    }
  }

  /**
   * Context-passing {@link #forEach(Object[], Consumer)}: pair a non-capturing {@link BiConsumer}
   * (typically a {@code static final}) with side-band {@code context} to avoid a per-call closure.
   */
  public static <C, E> void forEach(
      @Nonnull E[] table, C context, @Nonnull BiConsumer<? super C, ? super E> consumer) {
    for (final E e : table) {
      if (e != null) {
        consumer.accept(context, e);
      }
    }
  }

  /**
   * Read-only iterator over the entries sharing {@code hash} — walks the probe run from {@code
   * hash}'s home and yields each entry whose {@link HashStrategy#hashOf} equals {@code hash},
   * stopping at the first empty slot (the FlatHashtable analogue of walking a chained bucket).
   *
   * <p>This general overload holds the strategy in a field, so {@code hashOf} is called virtually
   * (not inlined). For {@link Entry}-based tables prefer {@link #iterator(Entry[], long)}, which
   * specializes the traversal so {@code hashOf} inlines. (Still pass a {@code static final}
   * strategy to avoid a per-call allocation.)
   */
  @Nonnull
  public static <E> Iterator<E> iterator(
      @Nonnull E[] table, long hash, @Nonnull HashStrategy<E> hashStrat) {
    return new StrategyHashIterator<>(table, hash, hashStrat);
  }

  /**
   * {@link #iterator(Object[], long, HashStrategy)} for {@link Entry}-based tables: the filter hash
   * comes from {@link Entry#hash}, so no strategy is needed. Returns a specialized iterator that
   * feeds the {@link Entry}-hash strategy singleton into the shared traversal template as a <i>
   * constant</i>, so {@code hashOf} devirtualizes to {@code entry.hash} and inlines — a monomorphic
   * call site thus gets a devirtualized pull-based traversal while keeping the plain {@link
   * Iterator} API and reusing the same core (see the {@link HashIterator} base). Same {@code
   * Iterator<E>} return type as the general overload; the call site specializes by its own
   * monomorphism.
   */
  @Nonnull
  public static <E extends Entry> Iterator<E> iterator(@Nonnull E[] table, long hash) {
    return new EntryHashIterator<>(table, hash);
  }

  /**
   * Shared iterator core. The traversal lives in {@code final} template methods parameterized by
   * the strategy ({@code advanceWith}/{@code nextWith}); each concrete subclass implements {@link
   * #next} by handing in its strategy source — a field (general) or a {@code static final} constant
   * (Entry). Feeding a constant into the {@code final} template is what lets the specialized
   * subclass inline {@code hashOf} (the CacheHelper static-polymorphism move), so the two share all
   * the mechanism yet differ only in whether the strategy call devirtualizes.
   */
  private abstract static class HashIterator<E> implements Iterator<E> {
    final E[] table;
    final long hash;
    final int start;
    int i;
    boolean done;
    E lookahead;

    HashIterator(E[] table, long hash) {
      this.table = table;
      this.hash = hash;
      this.start = home(hash, table.length - 1);
      this.i = this.start;
      // Priming advance() is left to the concrete ctor: it needs the subclass's strategy source,
      // which isn't set until after super().
    }

    /**
     * Template traversal core, parameterized by the strategy. {@code final} so that a subclass
     * passing a constant strategy inlines this and devirtualizes {@code hashOf}.
     */
    final void advanceWith(HashStrategy<E> hashStrat) {
      lookahead = null;
      if (done) {
        return;
      }
      final int mask = table.length - 1;
      for (; ; ) {
        final E e = table[i];
        if (e == null) {
          done = true; // probe run ends at the first empty slot
          return;
        }
        final boolean match = hashStrat.hashOf(e) == hash;
        i = (i + 1) & mask;
        final boolean wrapped = (i == start);
        if (match) {
          lookahead = e;
          done = wrapped;
          return;
        }
        if (wrapped) {
          done = true; // walked the whole table without an empty slot
          return;
        }
      }
    }

    final E nextWith(HashStrategy<E> hashStrat) {
      final E e = lookahead;
      if (e == null) {
        throw new NoSuchElementException();
      }
      advanceWith(hashStrat);
      return e;
    }

    @Override
    public final boolean hasNext() {
      return lookahead != null;
    }

    // Abstract so each subclass injects its own strategy source into nextWith(); that binding is
    // what lets the Entry variant inline hashOf while the general one stays virtual.
    @Override
    public abstract E next();
  }

  /** General iterator: strategy held in a field, so {@code hashOf} stays a virtual call. */
  private static final class StrategyHashIterator<E> extends HashIterator<E> {
    private final HashStrategy<E> hashStrat;

    StrategyHashIterator(E[] table, long hash, HashStrategy<E> hashStrat) {
      super(table, hash);
      this.hashStrat = hashStrat;
      advanceWith(hashStrat); // prime
    }

    @Override
    public E next() {
      return nextWith(hashStrat);
    }
  }

  /**
   * Entry iterator: feeds the constant {@link #ENTRY_HASH} singleton, so {@code hashOf} inlines to
   * {@code entry.hash}.
   */
  private static final class EntryHashIterator<E extends Entry> extends HashIterator<E> {
    EntryHashIterator(E[] table, long hash) {
      super(table, hash);
      advanceWith(entryHash()); // prime with the constant
    }

    @Override
    public E next() {
      return nextWith(entryHash());
    }
  }

  /**
   * Stateless {@link HashStrategy} that reads the cached {@link Entry#hash} — the constant fed into
   * the Entry iterator template so {@code hashOf} devirtualizes.
   */
  private static final HashStrategy<Entry> ENTRY_HASH = entry -> entry.hash;

  @SuppressWarnings("unchecked")
  private static <E> HashStrategy<E> entryHash() {
    return (HashStrategy<E>) ENTRY_HASH; // safe: hashOf only reads Entry.hash, present on all E
  }
}
