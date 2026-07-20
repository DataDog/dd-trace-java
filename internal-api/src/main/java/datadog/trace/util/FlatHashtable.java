package datadog.trace.util;

import datadog.trace.api.function.Strategy;
import datadog.trace.api.function.StrategyConsumer;
import java.lang.reflect.Array;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Open-addressed, single-array find-or-create over <b>self-contained entries</b> — each slot is one
 * reference to an entry that carries its own key (and, typically, a cached hash). One array, one
 * reference per slot: entry publication is a single reference store, so a reader sees {@code null}
 * or a complete entry (never a torn one), and {@code final} identity fields on the entry are
 * visible under racy publication. That sidesteps the memory-ordering / visibility problems parallel
 * key/hash/value arrays would create — no {@code volatile}, no atomics — as long as the payload is
 * one where a stale/lost read is benign (miss → recreate; clobber → one wins).
 *
 * <p><b>Bounded by construction</b> — and that is a feature, not just a limit. {@link #create}
 * takes a cardinality budget, so you cannot build one without deciding <i>how big it may get</i> —
 * the question whose unasked version becomes an unbounded-growth leak in a long-lived agent living
 * in someone else's process. A regular {@code Map}'s auto-resize lets you forget that (fine when
 * you own the heap; the wrong default when you are a guest in one). This table never grows on its
 * own: {@link #get} / {@link #getOrCreate} / {@link #insert} <i>cap</i> rather than churn — a full
 * table degrades to recompute-on-miss with bounded memory and no reallocation — and growth is an
 * explicit, deliberate {@link #resize} / {@link #resizingInsert}. So it defaults to the
 * bounded-footprint posture the agent needs, with unbounded growth an opt-in you have to reach for
 * (and one that, over externally-controlled keys, is the leak this structure otherwise prevents —
 * see {@link #resizingInsert}). The trade only pays when a miss is benign (a cache / interner), not
 * for a must-hold-everything map.
 *
 * <p><b>Strategy roles, split by concern.</b> The per-use policy is a small set of {@link Strategy
 * strategy} objects rather than one, so a caller supplies only what an operation needs:
 *
 * <ul>
 *   <li>a {@link MatchingStrategy} — the <i>key side</i>: {@link MatchingStrategy#hashKey hash a
 *       lookup key} (defaults to {@code hashCode}) and {@link MatchingStrategy#matches match} it
 *       against a stored entry. Used by {@link #get} / {@link #getOrCreate}.
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
 * E e = FlatHashtable.getOrCreate(table, key, S, MyEntry::new);  // non-capturing create
 * }</pre>
 *
 * <p><b>Contract:</b> {@code table.length} must be a power of two ({@link #capacityFor}). Both
 * {@link MatchingStrategy#hashKey} and {@link HashStrategy#hashOf} may return a plain {@code
 * hashCode} — the table owns the spread ({@link #home}) — but they must be <b>consistent</b>:
 * {@code hashKey(key)} must equal {@code hashOf(entry)} for that key's entry, so a lookup lands
 * where the entry was placed (trivially true when both default to {@code hashCode}). Cardinality
 * cap / overflow / a live-size counter are <b>caller policy</b> (this class is pure mechanism): a
 * capped caller does {@link #get} first, and only on a miss checks its budget before {@link
 * #getOrCreate} (so hits stay a single probe and the create path is warmup-rare).
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
    long hashOf(E entry);
  }

  /**
   * Key-side strategy: whether a stored {@code entry} is the one for a lookup {@code key} ({@link
   * #matches}), and how to hash that key ({@link #hashKey}). {@code hashKey} <b>defaults to {@code
   * key.hashCode()}</b> — override it only when the key's identity needs different hashing (e.g.
   * case-insensitive), and then keep it consistent with the table's {@link HashStrategy#hashOf}.
   * Used by {@link #get} / {@link #getOrCreate}.
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
    boolean matches(E entry, K key);

    /**
     * Hash of {@code key}; defaults to {@code key.hashCode()} (the table applies its own spread).
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
    E create(K key);
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
    return Integer.highestOneBit(min - 1) << 1;
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
  public static <E> E[] create(Class<E> type, int cardinalityLimit) {
    return (E[]) Array.newInstance(type, capacityFor(cardinalityLimit));
  }

  /**
   * {@link #create(Class, int)} at an explicit {@code loadFactor} (see {@link #capacityFor(int,
   * float)} and the {@link #DEFAULT_LOAD_FACTOR} / {@link #LOW_LOAD_FACTOR} constants).
   */
  @SuppressWarnings("unchecked")
  public static <E> E[] create(Class<E> type, int cardinalityLimit, float loadFactor) {
    return (E[]) Array.newInstance(type, capacityFor(cardinalityLimit, loadFactor));
  }

  /**
   * Existing entry for {@code key}, or {@code null}. Read-only — never creates. Single probe on a
   * hit; walks to the first empty slot (or all the way around) on a miss.
   */
  @StrategyConsumer
  public static <E, K> E get(E[] table, K key, MatchingStrategy<E, K> matchStrat) {
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
  public static <E, K> E getOrCreate(
      E[] table, K key, MatchingStrategy<E, K> matchStrat, CreateStrategy<E, K> createStrat) {
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
  public static <E extends Entry> boolean insert(E[] table, E entry) {
    return placeAt(table, entry, entry.hash);
  }

  /**
   * {@link #insert(Entry[], Entry)} for any entry type: the home comes from {@link
   * HashStrategy#hashOf}. Same comparison-free, caller-ensures-absence contract.
   */
  @StrategyConsumer
  public static <E> boolean insert(E[] table, E entry, HashStrategy<E> hashStrat) {
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
  public static <E extends Entry> E[] resize(E[] table) {
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
  public static <E> E[] resize(E[] table, HashStrategy<E> hashStrat) {
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
    return (E[]) Array.newInstance(table.getClass().getComponentType(), table.length << 1);
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
   */
  public static <E extends Entry> E[] resizingInsert(E[] table, E entry) {
    E[] t = table;
    while (!insert(t, entry)) {
      t = resize(t); // one doubling always suffices; the loop is belt-and-braces
    }
    return t;
  }

  /**
   * {@link #resizingInsert(Entry[], Entry)} for any entry type (home via {@link
   * HashStrategy#hashOf}). Same grows-unboundedly caution.
   */
  @StrategyConsumer
  public static <E> E[] resizingInsert(E[] table, E entry, HashStrategy<E> hashStrat) {
    E[] t = table;
    while (!insert(t, entry, hashStrat)) {
      t = resize(t, hashStrat);
    }
    return t;
  }

  /** Applies {@code consumer} to every entry in {@code table} (skipping empty slots); any order. */
  public static <E> void forEach(E[] table, Consumer<? super E> consumer) {
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
      E[] table, C context, BiConsumer<? super C, ? super E> consumer) {
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
  public static <E> Iterator<E> iterator(E[] table, long hash, HashStrategy<E> hashStrat) {
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
  public static <E extends Entry> Iterator<E> iterator(E[] table, long hash) {
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
