package datadog.trace.util;

import datadog.environment.ThreadSupport;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A striped, lock-free counter primitive keyed by enum ordinal: {@code LongAdder}'s write
 * scalability, but as one shared, thread-sharded table instead of one independent {@code LongAdder}
 * per counter -- which avoids paying {@code LongAdder}'s per-instance striping overhead {@code
 * E.values().length} times over.
 *
 * <pre>{@code
 * enum MyCounters { FOO, BAR }
 *
 * Accumulator<MyCounters> counters = Accumulator.of(MyCounters.values());
 * counters.inc(MyCounters.FOO);
 * counters.add(MyCounters.BAR, 5L);
 *
 * Accumulator.Counts<MyCounters> drained = counters.accumulateAndReset();
 * long foo = drained.get(MyCounters.FOO);
 * }</pre>
 *
 * <p>Each counter's own {@link #accumulateAndReset} slot is read-and-zeroed with a single atomic
 * {@code getAndSet}, so -- like {@code Accumulator}'s previous {@code synchronized}-stripe design,
 * and unlike {@code LongAdder#sumThenReset()} -- no individual increment can land in the gap
 * between summing and zeroing and be silently lost. What's gone is the previous design's
 * <em>row-wide</em> atomicity: {@link #inc}/{@link #add} for two different counters are no longer
 * guaranteed to be seen together by a concurrent {@link #accumulateAndReset}. There is no {@code
 * update}-style escape hatch for grouping several counters under one atomic operation -- callers
 * needing that must weigh whether the guarantee was load-bearing (most call sites are logging
 * unrelated aspects of the same event, not maintaining a cross-counter invariant a reader depends
 * on) or bring their own coordination.
 */
public final class Accumulator<E extends Enum<E>> {
  /** One full cache line of {@code long}s (64 bytes), used to pad each stripe row. */
  private static final int CACHE_LINE_LONGS = 8;

  private final AtomicLongArray[] data;
  private final int width;
  private final E[] values;

  private Accumulator(AtomicLongArray[] data, int width, E[] values) {
    this.data = data;
    this.width = width;
    this.values = values;
  }

  /**
   * @param values the enum constants naming each counter, e.g. {@code MyCounters.values()}
   */
  public static <E extends Enum<E>> Accumulator<E> of(E[] values) {
    int width = values.length;
    int paddedWidth = paddedWidth(width);
    int stripes = stripeCount();
    AtomicLongArray[] data = new AtomicLongArray[stripes];
    for (int i = 0; i < stripes; i++) {
      data[i] = new AtomicLongArray(paddedWidth);
    }
    return new Accumulator<>(data, width, values);
  }

  /**
   * @param enumType the enum naming each counter, e.g. {@code MyCounters.class}
   */
  public static <E extends Enum<E>> Accumulator<E> of(Class<E> enumType) {
    return of(enumType.getEnumConstants());
  }

  /** Increments the counter named by {@code key} in the calling thread's stripe by one. */
  public void inc(E key) {
    add(key, 1L);
  }

  /** Adds {@code delta} to the counter named by {@code key} in the calling thread's stripe. */
  public void add(E key, long delta) {
    stripeOf(data).getAndAdd(key.ordinal(), delta);
  }

  /**
   * Combines and resets every stripe, returning the sum as a typed view. Each counter is
   * read-and-zeroed with one atomic {@code getAndSet} -- see the class-level note on what atomicity
   * this does and doesn't provide across different counters.
   *
   * @return the sum, keyed by the enum's {@code ordinal()}
   */
  public Counts<E> accumulateAndReset() {
    long[] acc = new long[width];
    for (AtomicLongArray stripe : data) {
      for (int i = 0; i < width; i++) {
        acc[i] += stripe.getAndSet(i, 0L);
      }
    }
    return new Counts<>(acc, values);
  }

  /**
   * Combines every stripe without resetting it, returning the sum as a typed view -- a live,
   * non-destructive snapshot for a diagnostic read (e.g. {@code summary()}) that must not perturb
   * the delta a concurrent {@link #accumulateAndReset} on a reporting cadence is about to report.
   *
   * @return the sum, keyed by the enum's {@code ordinal()}
   */
  public Counts<E> sum() {
    long[] acc = new long[width];
    for (AtomicLongArray stripe : data) {
      for (int i = 0; i < width; i++) {
        acc[i] += stripe.get(i);
      }
    }
    return new Counts<>(acc, values);
  }

  /**
   * A typed view over a drained snapshot, returned by {@link #accumulateAndReset} or {@link #sum}:
   * the same enum-ordinal type checking {@link Accumulator} provides on writes, applied to the read
   * side too.
   */
  public static final class Counts<E extends Enum<E>> {
    private final long[] counts;
    private final E[] values;

    private Counts(long[] counts, E[] values) {
      this.counts = counts;
      this.values = values;
    }

    /**
     * An all-zero {@link Counts}, sized for {@code values} -- for seeding a running total before
     * any real drain has happened, without needing a scratch {@link Accumulator} just to call
     * {@link Accumulator#sum()} on it.
     *
     * @param values the enum constants naming each counter, e.g. {@code MyCounters.values()}
     */
    public static <E extends Enum<E>> Counts<E> zero(E[] values) {
      return new Counts<>(new long[values.length], values);
    }

    /**
     * @param enumType the enum naming each counter, e.g. {@code MyCounters.class}
     * @see #zero(Enum[])
     */
    public static <E extends Enum<E>> Counts<E> zero(Class<E> enumType) {
      return zero(enumType.getEnumConstants());
    }

    /** The counter named by {@code key}. */
    public long get(E key) {
      return counts[key.ordinal()];
    }

    /**
     * The enum constants this {@link Counts} is keyed by, in declaration order -- for a caller that
     * wants to iterate every counter (e.g. reporting each one) without separately having to pass
     * {@code E.values()} alongside this object.
     */
    public E[] keys() {
      return values;
    }

    /**
     * Adds {@code other} to this, key by key, returning a new {@link Counts} rather than mutating
     * either input -- combines a stored running total with a fresh, non-destructive {@link #sum} to
     * answer "what's the live total right now" without ever resetting anything.
     */
    public Counts<E> plus(Counts<E> other) {
      long[] combined = counts.clone();
      for (int i = 0; i < combined.length; i++) {
        combined[i] += other.counts[i];
      }
      return new Counts<>(combined, values);
    }
  }

  /**
   * The calling thread's stripe: cheap masking, no allocation, no map lookup.
   *
   * <p>Multiple threads can map to the same stripe (this is masking, not a bijection); each
   * counter's own atomic slot makes that safe, just not maximally scalable under a hash collision.
   */
  private static AtomicLongArray stripeOf(AtomicLongArray[] data) {
    int mask = data.length - 1;
    int idx = (int) (ThreadSupport.threadId() & mask);
    return data[idx];
  }

  /**
   * A fixed, power-of-two stripe count deliberately oversized to roughly 2x {@link
   * Runtime#availableProcessors()} (minimum 4). Not exposed as a per-call override: a mandatory
   * sizing knob on every caller fails the "print test" of self-explanatory API design.
   *
   * <p>Sizing to exactly the core count leaves stripe collisions likely under real contention
   * (birthday-paradox math: with {@code n} contending threads and {@code m} stripes, expected
   * colliding pairs are {@code n(n-1)/(2m)}) -- and a collision costs CAS-retry/cache-line-bounce
   * cost, the same problem {@code LongAdder}'s own {@code Cell[]} table exists to avoid. Doubling
   * the stripe count roughly halves that collision count for a one-time, per-accumulator memory
   * cost, at the price of a slightly more expensive (but far rarer) {@link #accumulateAndReset}
   * drain -- the right trade given {@link #inc}/{@link #add} run on every call while {@link
   * #accumulateAndReset} runs on a reporting cadence.
   */
  private static int stripeCount() {
    int cpus = Runtime.getRuntime().availableProcessors();
    return Math.max(4, 2 * Integer.highestOneBit(Math.max(1, cpus)));
  }

  /** Rounds {@code width} up to a whole number of cache lines, plus one full trailing line. */
  private static int paddedWidth(int width) {
    int wholeLines = ((width + CACHE_LINE_LONGS - 1) / CACHE_LINE_LONGS) * CACHE_LINE_LONGS;
    return wholeLines + CACHE_LINE_LONGS;
  }
}
