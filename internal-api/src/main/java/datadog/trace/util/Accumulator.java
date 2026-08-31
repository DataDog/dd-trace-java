package datadog.trace.util;

import datadog.environment.ThreadSupport;
import datadog.trace.api.function.Strategy;
import datadog.trace.api.function.StrategyConsumer;
import java.util.Arrays;
import java.util.function.Consumer;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.concurrent.GuardedBy;

/**
 * A striped accumulator primitive: {@code LongAdder}'s write scalability, without {@code
 * LongAdder}'s reset hazard.
 *
 * <p>{@code LongAdder#sumThenReset()} is documented as <b>not atomic</b> against concurrent
 * updates: an increment landing on a cell after it's summed but before it's zeroed is silently and
 * permanently lost. {@link #accumulateAndReset} closes that window by combining and resetting each
 * stripe under the same lock that guards its writers.
 *
 * <p>Each stripe's state is a bare {@code long[]}, not a named-field struct. An {@code enum}
 * assigns a name to each position via its ordinal, so name and position are the same declaration
 * and cannot drift apart. This also makes {@link #combine} and {@link #reset} generic, branchless,
 * fixed-trip-count array loops -- the shape designed to take advantage of SIMD / vector operations
 * on modern hardware -- so they are implemented once here instead of once per caller.
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
 * long[] drained = Accumulator.accumulateAndReset(data); // combine + reset, atomically per stripe
 * long foo = drained[MyCounters.FOO.ordinal()];
 * }</pre>
 *
 * <p>This class is a pure namespace over caller-owned {@code long[][]} state -- it allocates no
 * container object and is not itself a strategy consumer's receiver.
 */
@ParametersAreNonnullByDefault
public final class Accumulator {
  private Accumulator() {}

  /** One full cache line of {@code long}s (64 bytes), used to pad each stripe's row. */
  private static final int CACHE_LINE_LONGS = 8;

  /**
   * Creates the backing storage for an accumulator over {@code values}: one {@code long[]} row per
   * stripe, sized to {@code values.length} plus at least one trailing cache line of padding so
   * adjacent stripe rows don't false-share.
   *
   * <p>Stripe count is fixed at a power of two oversized to roughly 2x {@link
   * Runtime#availableProcessors()} (minimum 4); it is not a per-call knob (see {@link
   * #stripeCount()}).
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
   * #accumulateAndReset}.
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
  public static long[] accumulateAndReset(long[][] data) {
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
  @GuardedBy("stripe")
  private static void combine(long[] acc, long[] stripe) {
    for (int i = 0; i < acc.length; i++) {
      acc[i] += stripe[i];
    }
  }

  /** Zeroes every position of {@code stripe}, via the JVM-intrinsic {@link Arrays#fill}. */
  @GuardedBy("stripe")
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
   * colliding pairs are {@code n(n-1)/(2m)}) -- and a collision costs a blocking {@code
   * synchronized} wait, not a cheap CAS retry. Doubling the stripe count roughly quarters that
   * collision count for a one-time, per-accumulator memory cost, at the price of a slightly more
   * expensive (but far rarer) {@link #accumulateAndReset} drain -- the right trade given {@link
   * #inc}/ {@link #add} run on every call while {@link #accumulateAndReset} runs on a reporting
   * cadence.
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
