package datadog.trace.util;

import datadog.environment.ThreadSupport;
import datadog.trace.api.function.Strategy;
import datadog.trace.api.function.StrategyConsumer;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.concurrent.GuardedBy;

/**
 * A typed, instance-owning wrapper over {@link EmbeddingSupport}: ties an enum's type to its
 * backing {@code long[][]} at construction, so {@link #inc}/{@link #add} can't be called with a key
 * from a different enum than the one this accumulator was {@link #of created} for. Costs one
 * field-load indirection per call versus calling {@link EmbeddingSupport} directly -- the same
 * trade {@code StringIndex} makes over its own nested {@code EmbeddingSupport}.
 *
 * <pre>{@code
 * enum MyCounters { FOO, BAR }
 *
 * Accumulator<MyCounters> counters = Accumulator.of(MyCounters.values());
 * counters.inc(MyCounters.FOO);
 * counters.update(stripe -> {
 *   stripe.inc(MyCounters.FOO);
 *   stripe.inc(MyCounters.BAR);
 * });
 *
 * Accumulator.Counts<MyCounters> drained = counters.accumulateAndReset(); // atomically per stripe
 * long foo = drained.get(MyCounters.FOO);
 * }</pre>
 *
 * @see EmbeddingSupport
 */
public final class Accumulator<E extends Enum<E>> {
  private final long[][] data;
  private final E[] values;

  private Accumulator(long[][] data, E[] values) {
    this.data = data;
    this.values = values;
  }

  /**
   * @param values the enum constants naming each counter, e.g. {@code MyCounters.values()}
   */
  public static <E extends Enum<E>> Accumulator<E> of(E[] values) {
    return new Accumulator<>(EmbeddingSupport.create(values), values);
  }

  /**
   * @param enumType the enum naming each counter, e.g. {@code MyCounters.class}
   */
  public static <E extends Enum<E>> Accumulator<E> of(Class<E> enumType) {
    return of(enumType.getEnumConstants());
  }

  /** Increments the counter named by {@code key} in the calling thread's stripe by one. */
  public void inc(E key) {
    EmbeddingSupport.inc(data, key);
  }

  /** Adds {@code delta} to the counter named by {@code key} in the calling thread's stripe. */
  public void add(E key, long delta) {
    EmbeddingSupport.add(data, key, delta);
  }

  /**
   * Runs {@code mutator} against a typed view of the calling thread's stripe under a single held
   * lock -- the escape hatch for performing several related updates atomically with respect to a
   * concurrent {@link #accumulateAndReset}.
   *
   * @param mutator a strategy over the selected stripe; keep it small and non-capturing so it
   *     inlines into the lock's critical section, and don't let the {@link Stripe} escape it (store
   *     it, return it, hand it to another thread) -- see {@link Stripe}
   */
  @StrategyConsumer
  public void update(@Strategy Consumer<Stripe<E>> mutator) {
    long[] stripe = EmbeddingSupport.stripeOf(data);
    synchronized (stripe) {
      mutator.accept(new Stripe<>(stripe));
    }
  }

  /**
   * Like {@link #update(Consumer)}, but passes {@code context} to {@code mutator} as an explicit
   * parameter instead of letting the mutator capture it -- for a caller that would otherwise need
   * to close over a local (e.g. a count) just to get it into the critical section. Note {@code
   * context} is boxed if it's a primitive at the call site; that's a real allocation trade against
   * the capturing lambda it replaces, not a free win -- prefer this only when {@code context} would
   * otherwise be the only thing forcing a capture. For an {@code int} or {@code long} context, use
   * {@link #update(long, ObjLongConsumer)} instead to avoid that boxing entirely.
   *
   * @param context a value the mutator needs, passed in rather than captured
   * @param mutator a strategy over {@code context} and the selected stripe; keep it small and
   *     non-capturing so it inlines into the lock's critical section, and don't let the {@link
   *     Stripe} escape it (store it, return it, hand it to another thread) -- see {@link Stripe}
   */
  @StrategyConsumer
  public <C> void update(C context, @Strategy BiConsumer<C, Stripe<E>> mutator) {
    long[] stripe = EmbeddingSupport.stripeOf(data);
    synchronized (stripe) {
      mutator.accept(context, new Stripe<>(stripe));
    }
  }

  /**
   * Like {@link #update(Object, BiConsumer)}, but for a {@code long} context -- reuses the JDK's
   * {@link ObjLongConsumer} instead of the generic {@link BiConsumer}, so {@code context} is passed
   * as a primitive {@code long} rather than boxed into a {@link Long}. Covers an {@code int}
   * context too: it widens to {@code long} for free at the call site, no boxing either way. (A
   * dedicated {@code int} overload isn't offered alongside this one -- an {@code int} argument
   * would be ambiguous between the two, since it's an exact match for one and a free widening
   * conversion to the other, and unrelated functional-interface types block the usual most-specific
   * tiebreak.)
   *
   * <p>Note the parameter order this forces: {@link ObjLongConsumer#accept} takes {@code (T,
   * long)}, so the mutator sees the stripe first and the context second -- the opposite order from
   * {@link #update(Object, BiConsumer)}.
   *
   * @param context a primitive value the mutator needs, passed in rather than captured or boxed
   * @param mutator a strategy over the selected stripe and {@code context}; keep it small and
   *     non-capturing so it inlines into the lock's critical section, and don't let the {@link
   *     Stripe} escape it (store it, return it, hand it to another thread) -- see {@link Stripe}
   */
  @StrategyConsumer
  public void update(long context, @Strategy ObjLongConsumer<Stripe<E>> mutator) {
    long[] stripe = EmbeddingSupport.stripeOf(data);
    synchronized (stripe) {
      mutator.accept(new Stripe<>(stripe), context);
    }
  }

  /**
   * A typed view over one stripe, handed to an {@link #update} strategy: the same enum-ordinal type
   * checking {@link Accumulator} provides at the top level, applied inside the critical section
   * too.
   *
   * <p>Constructed fresh under the held lock on every {@link #update} call. A well-behaved {@link
   * Strategy} mutator -- small, non-capturing, and never storing or returning this object -- lets
   * escape analysis prove it doesn't escape the inlined call and scalar-replace it, so no
   * allocation survives to run time. Break those rules (capture it in a field, return it, hand it
   * to another thread) and it degrades to a real, per-call allocation instead of a compile-time
   * fiction with no correctness difference either way -- just a cost one.
   */
  public static final class Stripe<E extends Enum<E>> {
    private final long[] stripe;

    private Stripe(long[] stripe) {
      this.stripe = stripe;
    }

    /** Increments the counter named by {@code key} in this stripe by one. */
    public void inc(E key) {
      EmbeddingSupport.inc(stripe, key);
    }

    /** Adds {@code delta} to the counter named by {@code key} in this stripe. */
    public void add(E key, long delta) {
      EmbeddingSupport.add(stripe, key, delta);
    }
  }

  /**
   * Combines and resets every stripe, returning the sum as a typed view.
   *
   * @return the sum, keyed by the enum's {@code ordinal()}
   * @see EmbeddingSupport#accumulateAndReset
   */
  public Counts<E> accumulateAndReset() {
    return new Counts<>(EmbeddingSupport.accumulateAndReset(data), values);
  }

  /**
   * Combines every stripe without resetting it, returning the sum as a typed view -- a live,
   * non-destructive snapshot for a diagnostic read (e.g. {@code summary()}) that must not perturb
   * the delta a concurrent {@link #accumulateAndReset} on a reporting cadence is about to report.
   *
   * @return the sum, keyed by the enum's {@code ordinal()}
   * @see EmbeddingSupport#sum(long[][])
   */
  public Counts<E> sum() {
    return new Counts<>(EmbeddingSupport.sum(data), values);
  }

  /**
   * A typed view over a drained {@code long[]}, returned by {@link #accumulateAndReset} or {@link
   * #sum}: the same enum-ordinal type checking {@link Accumulator} provides on writes, applied to
   * the read side too.
   *
   * <p>Unlike {@link Stripe}, this is expected to escape -- the caller holds and reads it after the
   * call returns -- so it's a real, per-drain allocation, not a scalar-replacement candidate.
   * That's fine: {@link #accumulateAndReset} runs on a reporting cadence, not per {@link
   * #inc}/{@link #add} call.
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
   * The static, raw-array tier of the striped accumulator primitive: {@code LongAdder}'s write
   * scalability, without {@code LongAdder}'s reset hazard.
   *
   * <p>{@code LongAdder#sumThenReset()} is documented as <b>not atomic</b> against concurrent
   * updates: an increment landing on a cell after it's summed but before it's zeroed is silently
   * and permanently lost. {@link #accumulateAndReset} closes that window by combining and resetting
   * each stripe under the same lock that guards its writers.
   *
   * <p>Each stripe's state is a bare {@code long[]}, not a named-field struct. An {@code enum}
   * assigns a name to each position via its ordinal, so name and position are the same declaration
   * and cannot drift apart. This also makes {@link #combine} and {@link #reset} generic,
   * branchless, fixed-trip-count array loops -- the shape designed to take advantage of SIMD /
   * vector operations on modern hardware -- so they are implemented once here instead of once per
   * caller.
   *
   * <p>This is a pure namespace over caller-owned {@code long[][]} state -- it allocates no
   * container object and is not itself a strategy consumer's receiver. That means {@code create}'s
   * type parameter is not bound to the one later {@code inc}/{@code add} calls infer: nothing stops
   * a caller from indexing the same {@code long[][]} with a different enum than the one it was
   * {@link #create}d for, which silently reads/writes the wrong slot rather than failing to
   * compile. Prefer the owning {@link Accumulator} instance, which closes that hole for one
   * field-load indirection per call; reach for this class directly only when that indirection is
   * worth removing.
   *
   * <pre>{@code
   * enum MyCounters { FOO, BAR }
   *
   * long[][] data = Accumulator.EmbeddingSupport.create(MyCounters.values());
   * Accumulator.EmbeddingSupport.inc(data, MyCounters.FOO);
   * Accumulator.EmbeddingSupport.update(data, stripe -> {
   *   Accumulator.EmbeddingSupport.inc(stripe, MyCounters.FOO);
   *   Accumulator.EmbeddingSupport.inc(stripe, MyCounters.BAR);
   * });
   *
   * long[] drained = Accumulator.EmbeddingSupport.accumulateAndReset(data); // per stripe
   * long foo = drained[MyCounters.FOO.ordinal()];
   * }</pre>
   */
  @ParametersAreNonnullByDefault
  public static final class EmbeddingSupport {
    private EmbeddingSupport() {}

    /** One full cache line of {@code long}s (64 bytes), used to pad each stripe's row. */
    private static final int CACHE_LINE_LONGS = 8;

    /**
     * Creates the backing storage for an accumulator over {@code values}: one {@code long[]} row
     * per stripe, sized to {@code values.length} plus at least one trailing cache line of padding
     * so adjacent stripe rows don't false-share.
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
     * calling thread's selected row: {@code synchronized} is reentrant, so calling this here does
     * not deadlock or take a second lock.
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
     * Runs {@code mutator} against the calling thread's stripe under a single held lock -- the
     * escape hatch for performing several related updates atomically with respect to a concurrent
     * {@link #accumulateAndReset}.
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
     * Combines and resets every stripe, returning the sum. Each stripe is locked for exactly as
     * long as it takes to fold its values into the result and zero it -- the same lock held by
     * {@link #inc}/{@link #add}/{@link #update} -- so no writer can land an increment in the gap
     * between summing and zeroing the way {@code LongAdder#sumThenReset()} allows.
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
     * Combines every stripe without resetting it, returning the sum -- a live, non-destructive
     * snapshot for a diagnostic read that must not perturb the delta a concurrent {@link
     * #accumulateAndReset} on a reporting cadence is about to report.
     *
     * @return a new array the same length as one stripe's row, indexed by the enum's {@code
     *     ordinal()} for the positions actually in use (trailing padding positions are always zero)
     * @see #accumulateAndReset(long[][])
     */
    public static long[] sum(long[][] data) {
      long[] acc = new long[data[0].length];
      for (long[] stripe : data) {
        synchronized (stripe) {
          combine(acc, stripe);
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
     * synchronized} wait, not a cheap CAS retry. Doubling the stripe count roughly halves that
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
}
