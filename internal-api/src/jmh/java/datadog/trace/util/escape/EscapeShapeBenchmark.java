package datadog.trace.util.escape;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Minimal code shapes, each isolating one thing that is believed to decide whether C2 can delete a
 * short-lived object. Read {@code gc.alloc.rate.norm} — bytes per operation — not the timings: a
 * shape that scalar-replaces reports 0, and one that does not reports the object's real size. Run
 * it as
 *
 * <pre>./gradlew :internal-api:jmh -Pjmh.includes=EscapeShape -Pjmh.profilers=gc -PtestJvm=17
 * </pre>
 *
 * and read the same rows across {@code -PtestJvm} 8, 11, 17, 21 and 25. The point is the matrix of
 * shape against JDK, so that "will this allocate" stops being a question two people answer from
 * memory. Nothing here is specific to any one caller: the arms were written for the granted/refused
 * reservation of APMLP-1642's WorkQueue, and carry over unchanged to APMLP-1799's Maybe&lt;T&gt;,
 * because both are the same two-outcome wrapper and the shapes are what is measured.
 *
 * <p>Every arm consumes the object's <em>fields</em> rather than the object. Handing the reference
 * to a {@link Blackhole} would make it escape by construction and every row would read the same.
 *
 * <p>Bytes per operation, one machine, {@code -Pjmh.forks=1}. A 16-byte object allocated on half
 * the operations reads as 8. Columns are the JDK the <em>fork</em> ran on, which is not necessarily
 * the JDK on the shell's path — take it from JMH's own {@code # VM version} line.
 *
 * <pre>
 * shape                                 JDK 8  JDK 11  JDK 17  JDK 21  JDK 25   what it isolates
 * singleSite                                0       ?       0       ?       0   the floor
 * flagOnOneAllocation                       0       ?       0       ?       0   outcome in a field
 * closedInFinally                           0       ?       0       ?       0   try/finally
 * closedInFinallyWithThrow                  0       ?       0       ?       ?   ... with the handler taken
 * flagOnOneAllocationClosedInFinally        0       ?       0       ?       0   flag field, whole
 * passedToInlinedStrategy                   0       ?       0       ?       0   @Strategy boundary
 * backingMonomorphic                        0       ?       0       ?       0   one backing
 * backingBimorphic                          0       ?       0       ?       0   two backings
 * phiWithNull                               8       ?       8       ?       0   merge with null
 * phiWithStatic                             8       ?       8       ?       8   merge with a singleton
 * phiWithStaticClosedInFinally              8       ?       8       ?       8   ... the same, whole
 * phiOfTwoAllocations                      16       ?      16       ?      16   merge of two allocations
 * passedToUninlinedStrategy                24       ?      24       ?      24   the same boundary, uninlined
 * backingMegamorphic                       24       ?      24       ?      24   three backings
 * </pre>
 *
 * <p>JDK 8 column measured 2026-08-27 (Zulu 8.72.0.17, this machine, {@code -Pjmh.fork=1}): every
 * arm lands on the same B/op as the 17/25 columns it was checked against, including {@code
 * phiWithNull} staying at 8 rather than following JDK 25's drop to 0 — the {@code
 * ReduceAllocationMerges} relaxation is JDK 21+ only, so 8's floor for this shape is the older,
 * unconditional one.
 *
 * <p>What the two measured columns say so far:
 *
 * <ul>
 *   <li>try/finally is free, including with the handler taken often enough to be compiled rather
 *       than left as an uncommon trap. It was the suspected culprit and it is not one. Note the
 *       catch is in the same method, so C2 can reduce the throw to control flow; this does not
 *       exercise an unwind through frames.
 *   <li>A merge with a static allocates on every JDK measured, JDK 25 included. The JDK 21
 *       allocation-merge work shows up only in the {@code phiWithNull} row, which goes 8 to 0; a
 *       merge of two live allocations still allocates at 25, because the merged reference is called
 *       through rather than only read from.
 *   <li>Moving the outcome into a field of a single allocation costs nothing, with or without the
 *       {@code finally}. That is the whole fix.
 *   <li>Inlining is the gate, and the strategy discipline is what holds it open: the same object
 *       through the same call boundary is 0 when the callee inlines and 24 when it does not.
 *   <li>Two backings behind a template method are free; three are not. The inheritance layout is
 *       not costing anything today, and would cost 24 bytes an operation the day a third arrives.
 * </ul>
 */
@Fork(
    value = 2,
    jvmArgsAppend = {
      "-XX:CompileCommand=dontinline,datadog.trace.util.escape.EscapeShapeBenchmark$UninlinedStrategy::apply"
    })
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class EscapeShapeBenchmark {

  /** Stands in for a reservation: two fields and a method worth calling. */
  interface Cell {
    int value();

    void close();
  }

  static final class Granted implements Cell {
    private final int seed;

    Granted(int seed) {
      this.seed = seed;
    }

    @Override
    public int value() {
      return seed + 1;
    }

    @Override
    public void close() {}
  }

  /** A second allocation site, for the merge that C2 has some chance with. */
  static final class Alternate implements Cell {
    private final int seed;

    Alternate(int seed) {
      this.seed = seed;
    }

    @Override
    public int value() {
      return seed + 2;
    }

    @Override
    public void close() {}
  }

  /** The shared refusal: reachable from a static, so the merge it takes part in is not local. */
  static final Cell REFUSED =
      new Cell() {
        @Override
        public int value() {
          return 0;
        }

        @Override
        public void close() {}
      };

  /** One allocation site carrying the outcome in a field: the shape that survives. */
  static final class Flagged {
    private final boolean granted;
    private final int seed;

    Flagged(boolean granted, int seed) {
      this.granted = granted;
      this.seed = seed;
    }

    int value() {
      return granted ? seed + 1 : 0;
    }

    void close() {}
  }

  /**
   * A non-capturing strategy held in a static final field of concrete type, as {@code @Strategy}
   * requires.
   */
  interface CellStrategy {
    int apply(Flagged cell);
  }

  static final CellStrategy INLINED = Flagged::value;

  /**
   * Kept out of line by the {@code CompileCommand} in {@link Fork}, not by {@link CompilerControl}:
   * JMH's processor only collects that annotation from {@code @Benchmark} methods, so putting it
   * here emits no hint at all and the arm silently becomes a duplicate of the inlined one. Check
   * the timing against {@code passedToInlinedStrategy} before believing this row — a call that
   * really did not inline cannot cost the same as no call.
   */
  static final class UninlinedStrategy implements CellStrategy {
    @Override
    public int apply(Flagged cell) {
      return cell.value();
    }
  }

  static final CellStrategy UNINLINED = new UninlinedStrategy();

  /**
   * The template-method shape: a final method on a base type calling out to an abstract one, with
   * the object under test riding along as the argument. How many concrete subclasses are loaded is
   * the whole experiment — C2 inlines a monomorphic call outright and a bimorphic one behind a type
   * guard, but gives up at three, and a call it does not inline turns its argument into an escape.
   */
  abstract static class Backing {
    final int admit(Flagged cell) {
      return store(cell);
    }

    abstract int store(Flagged cell);
  }

  static final class ArrayBacking extends Backing {
    @Override
    int store(Flagged cell) {
      return cell.value();
    }
  }

  static final class LinkedBacking extends Backing {
    @Override
    int store(Flagged cell) {
      return cell.value() + 1;
    }
  }

  static final class ThirdBacking extends Backing {
    @Override
    int store(Flagged cell) {
      return cell.value() + 2;
    }
  }

  // All three the same length, so the index arithmetic and the bounds check are identical and the
  // only difference between the arms is how many types reach the call site.
  //
  // Unexplained: the monomorphic arm times slower than the bimorphic one (2.14 against 1.26 ns on
  // 17), and equalising the lengths did not change it, so it is not the index arithmetic. Both
  // eliminate their allocation, which is what this matrix is for, so the timing oddity does not
  // touch any conclusion drawn here — but do not quote these two timings against each other until
  // someone has read the assembly.
  private final Backing[] one = {new ArrayBacking(), new ArrayBacking(), new ArrayBacking()};
  private final Backing[] two = {new ArrayBacking(), new LinkedBacking(), new ArrayBacking()};
  private final Backing[] three = {new ArrayBacking(), new LinkedBacking(), new ThirdBacking()};

  // The three arms below are deliberately copy-pasted rather than sharing a helper. A shared helper
  // would carry one profile for all three call sites, so the megamorphic arm would poison the other
  // two and the matrix would report the same answer three times.

  @Benchmark
  public void backingMonomorphic(Blackhole bh) {
    Backing backing = one[(counter++ & 0x7fffffff) % one.length];
    Flagged cell = new Flagged(true, counter);
    bh.consume(backing.admit(cell));
  }

  @Benchmark
  public void backingBimorphic(Blackhole bh) {
    Backing backing = two[(counter++ & 0x7fffffff) % two.length];
    Flagged cell = new Flagged(true, counter);
    bh.consume(backing.admit(cell));
  }

  @Benchmark
  public void backingMegamorphic(Blackhole bh) {
    Backing backing = three[(counter++ & 0x7fffffff) % three.length];
    Flagged cell = new Flagged(true, counter);
    bh.consume(backing.admit(cell));
  }

  /**
   * Alternates so both sides of every branch are taken and the profile is honest. A branch C2 never
   * sees taken becomes an uncommon trap, which would quietly turn the merge arms into single-site
   * arms and make the whole matrix a lie.
   */
  private int counter;

  private boolean alternate() {
    return (counter++ & 1) == 0;
  }

  @Benchmark
  public void singleSite(Blackhole bh) {
    Granted cell = new Granted(counter++);
    bh.consume(cell.value());
  }

  @Benchmark
  public void phiOfTwoAllocations(Blackhole bh) {
    Cell cell = alternate() ? new Granted(counter) : new Alternate(counter);
    bh.consume(cell.value());
  }

  @Benchmark
  public void phiWithStatic(Blackhole bh) {
    Cell cell = alternate() ? new Granted(counter) : REFUSED;
    bh.consume(cell.value());
  }

  @Benchmark
  public void phiWithNull(Blackhole bh) {
    Granted cell = alternate() ? new Granted(counter) : null;
    bh.consume(cell == null ? 0 : cell.value());
  }

  @Benchmark
  public void flagOnOneAllocation(Blackhole bh) {
    Flagged cell = new Flagged(alternate(), counter);
    bh.consume(cell.value());
  }

  @Benchmark
  public void closedInFinally(Blackhole bh) {
    Granted cell = new Granted(counter++);
    try {
      bh.consume(cell.value());
    } finally {
      cell.close();
    }
  }

  /** Preallocated and stackless, so the arm measures control flow rather than fillInStackTrace. */
  static final class Failure extends RuntimeException {
    static final Failure INSTANCE = new Failure();

    private Failure() {
      super("failure", null, false, false);
    }
  }

  /**
   * The same try/finally, with the handler actually taken often enough to be compiled rather than
   * left as an uncommon trap. This is the case {@link #closedInFinally} does not cover: there, C2
   * has never seen the exception path, so there is no code for the object to be live into.
   */
  @Benchmark
  public void closedInFinallyWithThrow(Blackhole bh) {
    Granted cell = new Granted(counter++);
    try {
      if ((counter & 15) == 0) {
        throw Failure.INSTANCE;
      }
      bh.consume(cell.value());
    } catch (Failure failure) {
      bh.consume(cell.value() + 1);
    } finally {
      cell.close();
    }
  }

  /** The Optional-style shape, whole: a singleton for one outcome, under try/finally. */
  @Benchmark
  public void phiWithStaticClosedInFinally(Blackhole bh) {
    Cell cell = alternate() ? new Granted(counter) : REFUSED;
    try {
      bh.consume(cell.value());
    } finally {
      cell.close();
    }
  }

  /** The single-site shape, whole: one allocation carrying a flag, under try/finally. */
  @Benchmark
  public void flagOnOneAllocationClosedInFinally(Blackhole bh) {
    Flagged cell = new Flagged(alternate(), counter);
    try {
      bh.consume(cell.value());
    } finally {
      cell.close();
    }
  }

  /**
   * A non-escaping object handed across a call boundary the strategy discipline keeps inlinable.
   */
  @Benchmark
  public void passedToInlinedStrategy(Blackhole bh) {
    Flagged cell = new Flagged(alternate(), counter);
    bh.consume(INLINED.apply(cell));
  }

  /**
   * The same, with only the inlining taken away. Whatever this costs is what the discipline buys.
   */
  @Benchmark
  public void passedToUninlinedStrategy(Blackhole bh) {
    Flagged cell = new Flagged(alternate(), counter);
    bh.consume(UNINLINED.apply(cell));
  }
}
