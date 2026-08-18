package datadog.trace.util;

import java.lang.reflect.Array;

/**
 * Open-addressed, single-array find-or-create over <b>self-contained entries</b> — each slot is one
 * reference to an entry that carries its own key (and, typically, a cached hash). One array, one
 * reference per slot: entry publication is a single reference store, so a reader sees {@code null}
 * or a complete entry (never a torn one), and {@code final} identity fields on the entry are
 * visible under racy publication. That sidesteps the memory-ordering / visibility problems parallel
 * key/hash/value arrays would create — no {@code volatile}, no atomics — as long as the payload is
 * one where a stale/lost read is benign (miss → recreate; clobber → one wins).
 *
 * <p><b>Static polymorphism (C++-template-style).</b> The per-use policy is a {@link Helper} — a
 * <b>stateless</b> subclass held by each caller as a {@code static final} field <i>declared with
 * the concrete helper type</i> (not the {@code Helper} base):
 *
 * <pre>{@code
 * private static final MyHelper HELPER = new MyHelper();  // concrete type => exact type pinned
 * ...
 * V v = FlatHashtable.getOrCreate(table, key, HELPER);
 * }</pre>
 *
 * Because {@code HELPER} is a compile-time-constant of an exact type at the call site, once these
 * small {@code Support} methods inline the JIT devirtualizes and inlines {@code hash}/{@code
 * matches}/{@code create} — each call site specializes to straight-line code, one instantiation per
 * helper, with no CHA/type-profiling dependence. Keep the methods small so they inline; verify with
 * {@code -XX:+PrintInlining} (the failure mode is silent: it compiles and runs, just stays
 * megamorphic and slow). {@code Helper} is an abstract class, so a distinct final subclass is
 * required anyway — an exact type gives the inliner an unambiguous receiver.
 *
 * <p><b>Contract:</b> {@code table.length} must be a power of two ({@link #capacityFor}). {@code
 * helper.hash} should be well-distributed (this class masks it directly). Cardinality cap /
 * overflow / a live-size counter are <b>caller policy</b> (this class is pure mechanism): a capped
 * caller does {@link #get} first, and only on a miss checks its budget before {@link #getOrCreate}
 * (so hits stay a single probe and the create path is warmup-rare).
 */
public final class FlatHashtable {
  private FlatHashtable() {}

  /**
   * Per-use policy. Extend as a <b>stateless</b> final class and hold a {@code static final}
   * singleton of the concrete type (see class doc) so the JIT can specialize each call site.
   *
   * <p>An abstract <b>class</b> (not an interface) on purpose: it forces a named helper type (no
   * lambdas, which can blur the receiver the inliner needs), and if specialization ever misses, the
   * fallback dispatches via {@code invokevirtual} rather than the costlier megamorphic {@code
   * invokeinterface}. On the specialized (inlined) path the choice is a wash — this just hedges the
   * fallback and lets shared bits be {@code final}-sealed later.
   *
   * @param <K> lookup key
   * @param <V> stored entry — self-contained (carries its own key, ideally a cached hash)
   */
  public abstract static class Helper<K, V> {
    /** Hash of {@code key}; should be well-distributed (this table masks it directly). */
    public abstract int hash(K key);

    /** Whether the stored {@code value} entry is the one for {@code key}. */
    public abstract boolean matches(K key, V value);

    /** Mint a new entry for {@code key} (called once, on insert). */
    public abstract V create(K key);
  }

  /**
   * {@link Helper} specialized for {@code String} keys: seals a spread {@link #hash} so String-key
   * callers write only {@link #matches} and {@link #create}. Extend as a stateless final class held
   * in a concrete-typed {@code static final} singleton, exactly like {@link Helper} — the {@code
   * final} hash resolves directly and the concrete subclass still specializes the same at each call
   * site, so there's no cost to the extra layer.
   *
   * @param <V> stored entry — self-contained (carries its own key, ideally a cached hash)
   */
  public abstract static class StringHelper<V> extends Helper<String, V> {
    @Override
    public final int hash(String key) {
      final int h = key.hashCode();
      return h ^ (h >>> 16); // spread; FlatHashtable masks this directly
    }
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
   * Passing {@code type} makes the array's runtime component type {@code T} rather than {@code
   * Object[]} — typed reads, real array-store checks, and a monomorphic element type for the JIT.
   * Callers can't {@code new T[]} themselves under erasure; this does the one reflective allocation
   * at construction (off any hot path). Note: this {@code create} mints the backing array; {@link
   * Helper#create} mints an entry — different types, no ambiguity at the call site.
   */
  @SuppressWarnings("unchecked")
  public static <T> T[] create(Class<T> type, int cardinalityLimit) {
    return (T[]) Array.newInstance(type, capacityFor(cardinalityLimit));
  }

  /**
   * Existing entry for {@code key}, or {@code null}. Read-only — never creates. Single probe on a
   * hit; walks to the first empty slot (or all the way around) on a miss.
   */
  public static <K, V> V get(V[] table, K key, Helper<K, V> helper) {
    final int mask = table.length - 1;
    final int start = helper.hash(key) & mask;
    int i = start;
    for (; ; ) {
      final V e = table[i];
      if (e == null) {
        return null; // empty slot terminates the probe (no tombstones)
      }
      if (helper.matches(key, e)) {
        return e;
      }
      i = (i + 1) & mask;
      if (i == start) {
        return null; // wrapped ⇒ full, absent
      }
    }
  }

  /**
   * Existing entry for {@code key}, or a freshly {@link Helper#create created} + inserted one.
   * Returns {@code null} only if the table is full (no empty slot) — the caller supplies its
   * overflow default. The insert is a single plain reference store: a concurrent clobber /
   * double-create is acceptable only when the payload makes it benign (see class doc).
   */
  public static <K, V> V getOrCreate(V[] table, K key, Helper<K, V> helper) {
    final int mask = table.length - 1;
    final int start = helper.hash(key) & mask;
    int i = start;
    for (; ; ) {
      final V e = table[i];
      if (e == null) {
        final V created = helper.create(key);
        table[i] = created; // single-reference publish; benign clobber (see class doc)
        return created;
      }
      if (helper.matches(key, e)) {
        return e;
      }
      i = (i + 1) & mask;
      if (i == start) {
        return null; // wrapped ⇒ full
      }
    }
  }
}
