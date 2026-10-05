package datadog.trace.util;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What {@link AdaptiveLatch#tryApply} costs and saves for an operation whose failure depends on the
 * input. The operation is {@link Integer#parseInt}, whose {@link NumberFormatException} fills in a
 * full stack trace; the pre-check is a digit scan, which is correct (it flags only input {@code
 * parseInt} rejects) but not complete (it lets an overflowing number through).
 *
 * <ul>
 *   <li>{@code unguarded*}: the status quo -- parse, and catch the exception for bad input.
 *   <li>{@code precheck*}: the non-adaptive alternative -- always scan before parsing.
 *   <li>{@code latchGood}: a disengaged latch on good input. The difference from {@code
 *       unguardedGood} is the latch's overhead on the path that works.
 *   <li>{@code latchEngagedGood}: good input while engaged, so it pays for the pre-check too. The
 *       latch is pinned engaged ({@code closeAfter} is {@link Integer#MAX_VALUE}) so that the state
 *       holds for the whole run.
 *   <li>{@code latchBad}: bad input while engaged, the steady state for a producer that keeps
 *       sending garbage: turned away without parsing.
 *   <li>{@code mix*}: one bad input in every {@code invalidEveryN}, with the default {@code
 *       closeAfter}, so the latch engages, disengages and re-engages as it would in production.
 * </ul>
 *
 * <p>The cost of a throw grows with the depth of the stack it fills in, which is why {@code depth}
 * is a parameter: a benchmark thread's stack is shallow, a request thread's is not. Each arm
 * descends on its own, and each has its own {@code static final} latch, so that no arm's profile is
 * shaped by another's.
 *
 * <p>Run with {@code ./gradlew :internal-api:jmh -Pjmh.includes=AdaptiveLatchBenchmark
 * -Pjmh.profilers=gc}.
 *
 * <p>The latch here takes the pre-check form of a cautious path: the digit scan, then {@code
 * parseInt} only if it passes. The results were recorded when that was the latch's only form; it
 * does the same work now.
 *
 * <p>Results, one run: Zulu 17.0.7 (HotSpot), MacBook M1, single thread, 2 forks of 5 one-second
 * iterations, on a laptop with normal background activity. JDK 8 and x86 are not measured. ns/op is
 * derived from ops/s; B/op is from {@code -prof gc}. Good input allocates 16 B for the boxed result
 * in every arm.
 *
 * <pre>
 * ns/op (B/op)          depth 0          depth 50
 * unguardedGood         12.7  (16)        31.7  (16)
 * precheckGood          15.8  (16)        40.1  (16)
 * latchGood             15.7  (16)        36.2  (16)
 * latchEngagedGood      11.3  (16)        40.3  (16)
 * unguardedBad         908    (880)     2403    (2240)
 * precheckBad            2.79  (0)        25.6   (0)
 * latchBad               2.57  (0)        31.2   (0)
 *
 * Mix, ns/op, one bad input in every N
 *           depth 0                         depth 50
 *      N    unguarded  precheck  latch      unguarded  precheck  latch
 *      2        452       9.85    31.9         1238      33.7     98.7
 *    100       18.1      14.1     19.7         55.3      37.0     62.5
 *  10000       13.5      13.8     11.9         31.7      38.8     38.6
 * </pre>
 *
 * Engaged, bad input costs about 2.6 ns where the status quo costs about 908 ns (31 ns against 2.4
 * us at depth 50), and allocates nothing where the status quo allocates 880 B (2,240 B).
 *
 * <p>Disengaged, the latch is not free on good input: about 3 ns over {@code unguardedGood} at
 * depth 0 and about 4.5 ns at depth 50, more than one field read should cost. The depth-0
 * good-input arms have errors of 9 to 11%, which is also why {@code latchEngagedGood} appears
 * faster than {@code latchGood} there; the depth-50 gap is outside the error (2 to 3%). The cause
 * was not investigated.
 *
 * <p>The mix shows when the latch pays off. At one bad input in 2 it is about 14 times cheaper than
 * the status quo, though always pre-checking is cheaper still. At one in 100 it saves nothing and
 * costs a little: with the default {@code closeAfter} of 20, it disengages between bad inputs, so
 * every bad input still throws. It only helps while bad input arrives more often than once per
 * {@code closeAfter} calls; at one in 10,000 the three are within a few ns of each other.
 */
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(1)
@State(Scope.Benchmark)
public class AdaptiveLatchBenchmark {

  static final String GOOD = "1234567";
  static final String BAD = "12x4567";

  @Param({"0", "50"})
  int depth;

  /** Parses an int; while engaged, a digit scan turns away anything that is not plain digits. */
  static class Parse extends AdaptiveLatch<String, Integer, NumberFormatException> {
    Parse() {
      super(NumberFormatException.class);
    }

    @Override
    protected Integer apply(String input) {
      return Integer.parseInt(input);
    }

    @Override
    protected Integer applySafely(String input) {
      return isAllDigits(input) ? Integer.parseInt(input) : reject(input);
    }

    /** The value the recorded results were measured with; not tuned for this operation. */
    @Override
    protected int closeAfter() {
      return 20;
    }
  }

  /** Never disengages, so that the engaged state holds for a whole run of good input. */
  static final class PinnedParse extends Parse {
    @Override
    protected int closeAfter() {
      return Integer.MAX_VALUE;
    }
  }

  static boolean isAllDigits(String input) {
    if (input.isEmpty()) {
      return false;
    }
    // branch-free per char, as a pre-check over good input always scans it all anyway
    boolean digits = true;
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      digits &= c >= '0' & c <= '9';
    }
    return digits;
  }

  static Integer unguarded(String input) {
    try {
      return Integer.parseInt(input);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  static Integer precheck(String input) {
    return isAllDigits(input) ? Integer.parseInt(input) : null;
  }

  static final Parse LATCH_GOOD = new Parse();
  static final PinnedParse LATCH_ENGAGED_GOOD = new PinnedParse();
  static final Parse LATCH_BAD = new Parse();
  static final Parse LATCH_MIX = new Parse();

  /** Puts each latch in the state its arm measures, and fails fast if one does not get there. */
  @Setup
  public void setup() {
    if (LATCH_GOOD.tryApply(GOOD) == null || LATCH_GOOD.isEngaged()) {
      throw new IllegalStateException("good input must parse without engaging");
    }
    LATCH_ENGAGED_GOOD.tryApply(BAD);
    if (!LATCH_ENGAGED_GOOD.isEngaged() || LATCH_ENGAGED_GOOD.tryApply(GOOD) == null) {
      throw new IllegalStateException("good input must still parse while engaged");
    }
    LATCH_BAD.tryApply(BAD);
    if (!LATCH_BAD.isEngaged() || LATCH_BAD.tryApply(BAD) != null) {
      throw new IllegalStateException("bad input must engage the latch and be turned away");
    }
  }

  @Benchmark
  public Object unguardedGood() {
    return unguardedGood(depth);
  }

  @Benchmark
  public Object precheckGood() {
    return precheckGood(depth);
  }

  @Benchmark
  public Object latchGood() {
    return latchGood(depth);
  }

  @Benchmark
  public Object latchEngagedGood() {
    return latchEngagedGood(depth);
  }

  @Benchmark
  public Object unguardedBad() {
    return unguardedBad(depth);
  }

  @Benchmark
  public Object precheckBad() {
    return precheckBad(depth);
  }

  @Benchmark
  public Object latchBad() {
    return latchBad(depth);
  }

  /**
   * A deterministic stream: one {@link #BAD} in every {@code invalidEveryN}, else {@link #GOOD}.
   */
  @State(Scope.Thread)
  public static class Mix {
    @Param({"2", "100", "10000"})
    int invalidEveryN;

    int counter;

    String next() {
      if (++counter >= invalidEveryN) {
        counter = 0;
        return BAD;
      }
      return GOOD;
    }
  }

  @Benchmark
  public Object mixUnguarded(Mix mix) {
    return mixUnguarded(depth, mix.next());
  }

  @Benchmark
  public Object mixPrecheck(Mix mix) {
    return mixPrecheck(depth, mix.next());
  }

  @Benchmark
  public Object mixLatch(Mix mix) {
    return mixLatch(depth, mix.next());
  }

  private Object unguardedGood(int remaining) {
    return remaining > 0 ? unguardedGood(remaining - 1) : unguarded(GOOD);
  }

  private Object precheckGood(int remaining) {
    return remaining > 0 ? precheckGood(remaining - 1) : precheck(GOOD);
  }

  private Object latchGood(int remaining) {
    return remaining > 0 ? latchGood(remaining - 1) : LATCH_GOOD.tryApply(GOOD);
  }

  private Object latchEngagedGood(int remaining) {
    return remaining > 0 ? latchEngagedGood(remaining - 1) : LATCH_ENGAGED_GOOD.tryApply(GOOD);
  }

  private Object unguardedBad(int remaining) {
    return remaining > 0 ? unguardedBad(remaining - 1) : unguarded(BAD);
  }

  private Object precheckBad(int remaining) {
    return remaining > 0 ? precheckBad(remaining - 1) : precheck(BAD);
  }

  private Object latchBad(int remaining) {
    return remaining > 0 ? latchBad(remaining - 1) : LATCH_BAD.tryApply(BAD);
  }

  private Object mixUnguarded(int remaining, String input) {
    return remaining > 0 ? mixUnguarded(remaining - 1, input) : unguarded(input);
  }

  private Object mixPrecheck(int remaining, String input) {
    return remaining > 0 ? mixPrecheck(remaining - 1, input) : precheck(input);
  }

  private Object mixLatch(int remaining, String input) {
    return remaining > 0 ? mixLatch(remaining - 1, input) : LATCH_MIX.tryApply(input);
  }
}
