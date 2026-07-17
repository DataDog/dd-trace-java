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
 * <p><b>Two strategies, split by concern.</b> The per-use policy is two {@link Strategy strategy}
 * objects rather than one:
 *
 * <ul>
 *   <li>a {@link KeyStrategy} — how to {@link KeyStrategy#hash hash} a lookup key and {@link
 *       KeyStrategy#matches match} it against a stored entry. Key-identity is intrinsic to the key
 *       <i>type</i>, so this is shared and reused (one {@link StringKeyStrategy} serves every
 *       String-keyed table); it is on the hot path (every probe), so it is an abstract class held
 *       as a concrete-typed {@code static final} constant to specialize (see {@link Strategy}).
 *   <li>a {@link CreateStrategy} — how to mint an entry for a key. Creation varies per use case and
 *       is on the cold path (once per key, at warmup), so it is a {@link FunctionalInterface} you
 *       can supply as a <i>non-capturing</i> lambda.
 * </ul>
 *
 * <pre>{@code
 * private static final MyKeyStrategy KEYS = new MyKeyStrategy();   // concrete type => exact type pinned
 * ...
 * E e = FlatHashtable.getOrCreate(table, key, KEYS, MyEntry::new); // non-capturing create
 * }</pre>
 *
 * <p><b>Contract:</b> {@code table.length} must be a power of two ({@link #capacityFor}). {@code
 * KeyStrategy.hash} should be well-distributed (this class masks it directly). Cardinality cap /
 * overflow / a live-size counter are <b>caller policy</b> (this class is pure mechanism): a capped
 * caller does {@link #get} first, and only on a miss checks its budget before {@link #getOrCreate}
 * (so hits stay a single probe and the create path is warmup-rare).
 */
public final class FlatHashtable {
  private FlatHashtable() {}

  /**
   * Optional structure-free entry base carrying only a cached {@code hash} — an
   * <i>optimization</i>, not plumbing (open addressing needs no {@code next}), so extending it is
   * never required: bring any entry type and supply {@link KeyStrategy#hashOf} yourself instead.
   * Caller contract: {@code hash} must equal the table's {@link KeyStrategy#hash} for this entry's
   * key, so the entry lands where {@link #get} looks.
   */
  public abstract static class Entry {
    public final long hash;

    protected Entry(long hash) {
      this.hash = hash;
    }
  }

  /**
   * Key-identity strategy: how to {@link #hash} a lookup key and {@link #matches} it against a
   * stored entry. Extend as a <b>stateless</b> final class and hold a {@code static final}
   * singleton of the concrete type so the JIT can specialize each call site (see {@link Strategy}).
   *
   * <p>An abstract <b>class</b> (not an interface) on purpose: it forces a named strategy type (no
   * lambdas, which can blur the receiver the inliner needs), and if specialization ever misses the
   * fallback dispatches via {@code invokevirtual} rather than the costlier megamorphic {@code
   * invokeinterface}. Key-identity is the <i>hot</i> strategy (every probe), so it takes the
   * abstract-class rigor; creation is the cold one, hence {@link CreateStrategy} is a lambda-able
   * interface.
   *
   * @param <K> lookup key
   * @param <E> stored entry — self-contained (carries its own key)
   */
  @Strategy
  public abstract static class KeyStrategy<K, E> {
    /**
     * Hash of {@code key} ({@code long} for family-wide consistency with Hashtable /
     * ConcurrentHashtable and to leave room for composite keys); should be well-distributed (this
     * table masks it directly).
     */
    public abstract long hash(K key);

    /** Whether the stored {@code entry} is the one for {@code key}. */
    public abstract boolean matches(K key, E entry);

    /**
     * Hash of a stored {@code entry} — must equal {@link #hash}{@code (key)} for that entry's key,
     * so the entry lands where {@link #get} would look for it. Used by the entry-taking {@link
     * #insert(Object[], Object, KeyStrategy)} and {@link #iterator} (which have an entry, not a
     * key); {@link #get} lookups never call it. When the entry can surface its key this is
     * typically {@code hash(entry.key)}; when it caches its own hash (see {@link Entry}), {@link
     * EntryKeyStrategy} seals it to that field.
     */
    public abstract long hashOf(E entry);
  }

  /**
   * {@link KeyStrategy} specialized for {@code String} keys: seals a spread {@link #hash} so
   * String-key callers write only {@link #matches}. Extend as a stateless final class held in a
   * concrete-typed {@code static final} singleton, exactly like {@link KeyStrategy} — the {@code
   * final} hash resolves directly and the concrete subclass still specializes the same at each call
   * site, so there's no cost to the extra layer.
   *
   * @param <E> stored entry — self-contained (carries its own key)
   */
  public abstract static class StringKeyStrategy<E> extends KeyStrategy<String, E> {
    @Override
    public final long hash(String key) {
      final int h = key.hashCode();
      return h ^ (h >>> 16); // spread the int entropy; widened to the family-wide long hash width
    }
  }

  /**
   * {@link KeyStrategy} for entries that extend {@link Entry}: seals {@link #hashOf} to the entry's
   * cached {@code hash}. Callers still supply {@link #hash} and {@link #matches} (or start from
   * {@link StringKeyStrategy} for the {@code hash} seal too).
   *
   * @param <K> lookup key
   * @param <E> stored entry — must extend {@link Entry}
   */
  public abstract static class EntryKeyStrategy<K, E extends Entry> extends KeyStrategy<K, E> {
    @Override
    public final long hashOf(E entry) {
      return entry.hash;
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
   * @param <K> lookup key
   * @param <E> stored entry to create
   */
  @Strategy
  @FunctionalInterface
  public interface CreateStrategy<K, E> {
    E create(K key);
  }

  /** Power-of-two capacity for a cardinality budget: {@code >= 2 * limit} (load factor <= 0.5). */
  public static int capacityFor(int cardinalityLimit) {
    if (cardinalityLimit <= 0) {
      throw new IllegalArgumentException("cardinalityLimit must be positive: " + cardinalityLimit);
    }
    return Integer.highestOneBit(cardinalityLimit * 2 - 1) << 1;
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
   * Existing entry for {@code key}, or {@code null}. Read-only — never creates. Single probe on a
   * hit; walks to the first empty slot (or all the way around) on a miss.
   */
  @StrategyConsumer
  public static <K, E> E get(E[] table, K key, KeyStrategy<K, E> keyStrat) {
    final int mask = table.length - 1;
    final int start = (int) (keyStrat.hash(key) & mask);
    int i = start;
    for (; ; ) {
      final E e = table[i];
      if (e == null) {
        return null; // empty slot terminates the probe (no tombstones)
      }
      if (keyStrat.matches(key, e)) {
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
  public static <K, E> E getOrCreate(
      E[] table, K key, KeyStrategy<K, E> keyStrat, CreateStrategy<K, E> createStrat) {
    final int mask = table.length - 1;
    final int start = (int) (keyStrat.hash(key) & mask);
    int i = start;
    for (; ; ) {
      final E e = table[i];
      if (e == null) {
        final E created = createStrat.create(key);
        table[i] = created; // single-reference publish; benign clobber (see class doc)
        return created;
      }
      if (keyStrat.matches(key, e)) {
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
   * {@code false} if the table is full. Convenience over the {@link KeyStrategy}-taking overload
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
   * KeyStrategy#hashOf}. Same comparison-free, caller-ensures-absence contract (the key type is
   * irrelevant here — insert never hashes or matches a key).
   */
  @StrategyConsumer
  public static <E> boolean insert(E[] table, E entry, KeyStrategy<?, E> keyStrat) {
    return placeAt(table, entry, keyStrat.hashOf(entry));
  }

  /**
   * Shared placement core: probe from {@code hash}'s home to the first empty slot; false if full.
   */
  private static <E> boolean placeAt(E[] table, E entry, long hash) {
    final int mask = table.length - 1;
    final int start = (int) (hash & mask);
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
   * hash}'s home and yields each entry whose {@link KeyStrategy#hashOf} equals {@code hash},
   * stopping at the first empty slot (the FlatHashtable analogue of walking a chained bucket). The
   * key type is irrelevant, so any {@link KeyStrategy} for {@code E} works.
   *
   * <p>Deliberately <b>not</b> a {@link StrategyConsumer}: iteration goes through the {@link
   * Iterator} interface and calls {@code hashOf} virtually, so the strategy does not inline here —
   * this is a cold traversal, not a hot specialization site. (Still pass a {@code static final}
   * strategy to avoid a per-call allocation.)
   */
  public static <E> Iterator<E> iterator(E[] table, long hash, KeyStrategy<?, E> keyStrat) {
    return new HashIterator<>(table, hash, keyStrat);
  }

  private static final class HashIterator<E> implements Iterator<E> {
    private final E[] table;
    private final long hash;
    private final KeyStrategy<?, E> keyStrat;
    private final int start;
    private int i;
    private boolean done;
    private E lookahead;

    HashIterator(E[] table, long hash, KeyStrategy<?, E> keyStrat) {
      this.table = table;
      this.hash = hash;
      this.keyStrat = keyStrat;
      this.start = (int) (hash & (table.length - 1));
      this.i = this.start;
      advance();
    }

    private void advance() {
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
        final boolean match = keyStrat.hashOf(e) == hash;
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

    @Override
    public boolean hasNext() {
      return lookahead != null;
    }

    @Override
    public E next() {
      final E e = lookahead;
      if (e == null) {
        throw new NoSuchElementException();
      }
      advance();
      return e;
    }
  }
}
