package datadog.trace.util;

import datadog.trace.api.function.Strategy;
import datadog.trace.api.function.StrategyConsumer;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * A striped accumulator primitive: {@code LongAdder}'s write scalability, without {@code
 * LongAdder}'s reset hazard.
 *
 * <p>{@code LongAdder} is reached for reflexively as "a cheap atomic counter," but it solves a
 * narrower problem (genuine many-thread write contention) and its {@code sumThenReset()} is
 * documented as <b>not atomic</b> against concurrent updates: it walks its cells summing-then-
 * zeroing one at a time, so an increment landing on a cell after it's summed but before it's zeroed
 * is silently and permanently lost. {@link #accumulateAnd} closes that window by combining and
 * resetting each stripe under the same lock that guards its writers, so no increment can land in
 * the gap.
 *
 * <p>Each stripe's state is a bare {@code long[]}, not a named-field struct. An {@code enum}
 * assigns a name to each position via its ordinal, so name and position are the same declaration
 * and cannot drift apart. This also makes {@link #combine} and {@link #reset} generic, branchless,
 * fixed-trip-count array loops -- exactly the shape C2's superword optimizer reliably
 * auto-vectorizes -- so they are implemented once here instead of once per caller.
 *
 * <pre>{@code
 * enum MyCounters { FOO, BAR }
 *
 * long[][] data = Accumulator.create(MyCounters.values());
 * Accumulator.inc(data, MyCounters.FOO);
 * Accumulator.update(data, stripe -> {
 *   Accumulator.inc(stripe, MyCounters.FOO);
 *   Accumulator.inc(stripe, MyCounters.BAR);
 * });
 *
 * long[] drained = Accumulator.accumulateAnd(data); // combine + reset, atomically per stripe
 * long foo = drained[MyCounters.FOO.ordinal()];
 * }</pre>
 *
 * <p>Non-additive counters (max, "ever seen" bitmask, first-occurrence timestamp) are out of scope:
 * the per-stripe operation this class provides is {@code +=} via {@link #inc}/{@link #add},
 * combined with {@code +=} in {@link #combine}. A stripeable operator only needs to be associative
 * and commutative, not literally addition, but no such escape hatch is wired up here -- add one (a
 * caller-supplied {@code LongBinaryOperator} strategy) only when a real candidate needs it.
 *
 * <p>This class is a pure namespace over caller-owned {@code long[][]} state, in the same style as
 * {@link Hashtable} and {@link FlatHashtable} -- it allocates no container object and is not itself
 * a strategy consumer's receiver.
 *
 * <p><b>Not built here (deliberately):</b> a struct-{@code T}-per-stripe fallback, for a subsystem
 * whose per-stripe state doesn't fit named {@code long} slots, with mutate/combine/extract as
 * {@code @Strategy}-annotated seams -- reach for it only if a real candidate can't be expressed as
 * an enum-keyed {@code long[]}. Likewise a raw/embedded tier (caller owns the stripe array
 * directly, no owning container) -- a reserve tool for a future {@code dd-trace-core}
 * hottest-per-span-path candidate, not needed by the current reporting-cadence migration targets.
 * Neither is stubbed out; build it when a real caller needs it (see APMLP-1779).
 */
public final class Accumulator {
  private Accumulator() {}

  /** One full cache line of {@code long}s (64 bytes), used to pad each stripe's row. */
  private static final int CACHE_LINE_LONGS = 8;

  /**
   * Creates the backing storage for an accumulator over {@code values}: one {@code long[]} row per
   * stripe, sized to {@code values.length} plus at least one trailing cache line of padding so
   * adjacent stripe rows don't false-share.
   *
   * <p>Stripe count is fixed at a power of two derived from {@link Runtime#availableProcessors()};
   * it is not a per-call knob (see {@link #stripeCount()}).
   *
   * @param values the enum constants naming each counter, e.g. {@code MyCounters.values()}
   * @return a new {@code long[stripeCount][paddedWidth]} array, zero-initialized
   */
  public static <E extends Enum<E>> long[][] create(E[] values) {
    int paddedWidth = paddedWidth(values.length);
    int stripes = stripeCount();
    long[][] data = new long[stripes][];
    for (int i = 0; i < stripes; i++) {
      data[i] = new long[paddedWidth];
    }
    return data;
  }

  /**
   * Increments the counter named by {@code key} in the calling thread's stripe by one.
   *
   * <p>Convenience for the common case: selects the calling thread's stripe, takes its lock, and
   * increments. To perform several increments under a single held lock, use {@link #update}.
   */
  public static <E extends Enum<E>> void inc(long[][] data, E key) {
    add(data, key, 1L);
  }

  /**
   * Adds {@code delta} to the counter named by {@code key} in the calling thread's stripe.
   *
   * @see #inc(long[][], Enum)
   */
  public static <E extends Enum<E>> void add(long[][] data, E key, long delta) {
    add(stripeOf(data), key, delta);
  }

  /**
   * Increments the counter named by {@code key} in {@code stripe} by one, under {@code stripe}'s
   * own lock.
   *
   * <p>Intended for use inside an {@link #update} lambda, where {@code stripe} is already the
   * calling thread's selected row: {@code synchronized} is reentrant, so calling this here does not
   * deadlock or take a second lock.
   */
  public static <E extends Enum<E>> void inc(long[] stripe, E key) {
    add(stripe, key, 1L);
  }

  /**
   * Adds {@code delta} to the counter named by {@code key} in {@code stripe}, under {@code
   * stripe}'s own lock.
   *
   * @see #inc(long[], Enum)
   */
  public static <E extends Enum<E>> void add(long[] stripe, E key, long delta) {
    synchronized (stripe) {
      stripe[key.ordinal()] += delta;
    }
  }

  /**
   * Runs {@code mutator} against the calling thread's stripe under a single held lock -- the escape
   * hatch for performing several related updates atomically with respect to a concurrent {@link
   * #accumulateAnd}.
   *
   * @param mutator a strategy over the selected stripe; keep it small and non-capturing so it
   *     inlines into the lock's critical section
   */
  @StrategyConsumer
  public static void update(long[][] data, @Strategy Consumer<long[]> mutator) {
    long[] stripe = stripeOf(data);
    synchronized (stripe) {
      mutator.accept(stripe);
    }
  }

  /**
   * Combines and resets every stripe, returning the sum. Each stripe is locked for exactly as long
   * as it takes to fold its values into the result and zero it -- the same lock held by {@link
   * #inc}/{@link #add}/{@link #update} -- so no writer can land an increment in the gap between
   * summing and zeroing the way {@code LongAdder#sumThenReset()} allows.
   *
   * @return a new array the same length as one stripe's row, indexed by the enum's {@code
   *     ordinal()} for the positions actually in use (trailing padding positions are always zero)
   */
  public static long[] accumulateAnd(long[][] data) {
    long[] acc = new long[data[0].length];
    for (long[] stripe : data) {
      synchronized (stripe) {
        combine(acc, stripe);
        reset(stripe);
      }
    }
    return acc;
  }

  /**
   * {@code acc[i] += stripe[i]} for every index -- a fixed-trip-count loop C2 can auto-vectorize.
   */
  private static void combine(long[] acc, long[] stripe) {
    for (int i = 0; i < acc.length; i++) {
      acc[i] += stripe[i];
    }
  }

  /** Zeroes every position of {@code stripe}, via the JVM-intrinsic {@link Arrays#fill}. */
  private static void reset(long[] stripe) {
    Arrays.fill(stripe, 0L);
  }

  /**
   * The calling thread's stripe: cheap masking, no allocation, no map lookup.
   *
   * <p>Multiple threads can map to the same stripe (this is masking, not a bijection); each
   * stripe's own lock makes that safe, just not maximally scalable under a hash collision.
   */
  private static long[] stripeOf(long[][] data) {
    int mask = data.length - 1;
    int idx = (int) (Thread.currentThread().getId() & mask);
    return data[idx];
  }

  /**
   * A fixed, power-of-two stripe count sized to {@link Runtime#availableProcessors()} (rounded down
   * to the nearest power of two, minimum one). Not exposed as a per-call override: a mandatory
   * sizing knob on every caller fails the "print test" of self-explanatory API design.
   */
  private static int stripeCount() {
    int cpus = Runtime.getRuntime().availableProcessors();
    return Integer.highestOneBit(Math.max(1, cpus));
  }

  /** Rounds {@code width} up to a whole number of cache lines, plus one full trailing line. */
  private static int paddedWidth(int width) {
    int wholeLines = ((width + CACHE_LINE_LONGS - 1) / CACHE_LINE_LONGS) * CACHE_LINE_LONGS;
    return wholeLines + CACHE_LINE_LONGS;
  }
}
