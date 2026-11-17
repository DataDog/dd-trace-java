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
 * memory. Nothing here is specific to any one caller: the arms model a generic two-outcome wrapper
 * (something that is either present with a value or absent) and carry over unchanged to {@code
 * Maybe}, because the shapes under test are about the compiler's allocation behavior, not about
 * what the wrapped value represents.
 *
 * <p>The underlying idea, before the terminology: the compiler can sometimes prove a short-lived
 * object never needs to outlive the method that created it, and when it can, it skips putting that
 * object on the heap at all -- it keeps the object's fields as plain local values instead. Three
 * terms for that recur below. <b>Escape analysis (EA)</b> is the compiler's proof step -- showing
 * an allocated object's lifetime is confined to the method (or thread) that created it, i.e. it
 * never escapes into a field, a return value visible outside, or a call the compiler cannot see
 * into. <b>Scalar replacement</b> is what C2 (HotSpot's JIT) does once that proof holds: the object
 * itself disappears, and its individual fields live in registers or on the stack instead, so no
 * heap allocation happens -- the arms below that read 0 B/op are exactly the ones EA proved safe.
 * <b>{@code ReduceAllocationMerges}</b> (JDK-8287061) extends that same proof to one harder case:
 * an if/else (or similar branch) where each side allocates its own object -- say {@code x = new
 * Foo()} in one branch and {@code x = new Bar()} in the other -- and the code after the branch
 * reads {@code x} without knowing which allocation actually ran. Before JDK 21, C2 could not
 * scalar-replace either allocation once they were merged like this, even if each individually would
 * have qualified on its own; {@code ReduceAllocationMerges} is what lets it do so starting at JDK
 * 21, which is why a few rows below only drop to 0 starting at JDK 21/25 rather than on every JDK.
 * All of this is specific to HotSpot's C2 JIT; none of it has been checked against OpenJ9 or
 * GraalVM, which use different compilers with different heuristics and may not scalar-replace the
 * same shapes.
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
 * mergeWithNull                             8       ?       8       ?       0   merge with null
 * mergeWithStatic                           8       ?       8       ?       8   merge with a singleton
 * mergeWithStaticClosedInFinally            8       ?       8       ?       8   ... the same, whole
 * mergeOfTwoAllocations                    16       ?      16       ?      16   merge of two allocations
 * passedToUninlinedStrategy                24       ?      24       ?      24   the same boundary, uninlined
 * backingMegamorphic                       24       ?      24       ?      24   three backings
 * </pre>
 *
 * <p>JDK 8 column measured 2026-08-27 (Zulu 8.72.0.17, this machine, {@code -Pjmh.fork=1}): every
 * arm lands on the same B/op as the 17/25 columns it was checked against, including {@code
 * mergeWithNull} staying at 8 rather than following JDK 25's drop to 0 — the {@code
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
 *       allocation-merge work shows up only in the {@code mergeWithNull} row, which goes 8 to 0; a
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

  /**
   * Minimal two-method interface -- a value to read and a close to call -- standing in for any
   * short-lived object more complex than a single field.
   */
  interface Outcome {
    int value();

    void close();
  }

  static final class SingleAllocation implements Outcome {
    private final int seed;

    SingleAllocation(int seed) {
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
  static final class AlternateAllocation implements Outcome {
    private final int seed;

    AlternateAllocation(int seed) {
      this.seed = seed;
    }

    @Override
    public int value() {
      return seed + 2;
    }

    @Override
    public void close() {}
  }

  /** The absent outcome, reachable from a static, so the merge it takes part in is not local. */
  static final Outcome STATIC_SINGLETON =
      new Outcome() {
        @Override
        public int value() {
          return 0;
        }

        @Override
        public void close() {}
      };

  /** One allocation site carrying the outcome in a field: the shape that survives. */
  static final class FlaggedAllocation {
    private final boolean present;
    private final int seed;

    FlaggedAllocation(boolean present, int seed) {
      this.present = present;
      this.seed = seed;
    }

    int value() {
      return present ? seed + 1 : 0;
    }

    void close() {}
  }

  /**
   * A non-capturing strategy held in a static final field of concrete type, as {@code @Strategy}
   * requires.
   */
  interface OutcomeStrategy {
    int apply(FlaggedAllocation cell);
  }

  static final OutcomeStrategy INLINED = FlaggedAllocation::value;

  /**
   * Kept out of line by the {@code CompileCommand} in {@link Fork}, not by {@link CompilerControl}:
   * JMH's processor only collects that annotation from {@code @Benchmark} methods, so putting it
   * here emits no hint at all and the arm silently becomes a duplicate of the inlined one. Check
   * the timing against {@code passedToInlinedStrategy} before believing this row — a call that
   * really did not inline cannot cost the same as no call.
   */
  static final class UninlinedStrategy implements OutcomeStrategy {
    @Override
    public int apply(FlaggedAllocation cell) {
      return cell.value();
    }
  }

  static final OutcomeStrategy UNINLINED = new UninlinedStrategy();

  /**
   * The template-method shape: a final method on a base type calling out to an abstract one, with
   * the object under test riding along as the argument. How many concrete subclasses are loaded is
   * the whole experiment — C2 inlines a monomorphic call outright and a bimorphic one behind a type
   * guard, but gives up at three, and a call it does not inline turns its argument into an escape.
   */
  abstract static class Backing {
    final int admit(FlaggedAllocation cell) {
      return store(cell);
    }

    abstract int store(FlaggedAllocation cell);
  }

  static final class ArrayBacking extends Backing {
    @Override
    int store(FlaggedAllocation cell) {
      return cell.value();
    }
  }

  static final class LinkedBacking extends Backing {
    @Override
    int store(FlaggedAllocation cell) {
      return cell.value() + 1;
    }
  }

  static final class ThirdBacking extends Backing {
    @Override
    int store(FlaggedAllocation cell) {
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
    FlaggedAllocation cell = new FlaggedAllocation(true, counter);
    bh.consume(backing.admit(cell));
  }

  @Benchmark
  public void backingBimorphic(Blackhole bh) {
    Backing backing = two[(counter++ & 0x7fffffff) % two.length];
    FlaggedAllocation cell = new FlaggedAllocation(true, counter);
    bh.consume(backing.admit(cell));
  }

  @Benchmark
  public void backingMegamorphic(Blackhole bh) {
    Backing backing = three[(counter++ & 0x7fffffff) % three.length];
    FlaggedAllocation cell = new FlaggedAllocation(true, counter);
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
    SingleAllocation cell = new SingleAllocation(counter++);
    bh.consume(cell.value());
  }

  @Benchmark
  public void mergeOfTwoAllocations(Blackhole bh) {
    Outcome cell = alternate() ? new SingleAllocation(counter) : new AlternateAllocation(counter);
    bh.consume(cell.value());
  }

  @Benchmark
  public void mergeWithStatic(Blackhole bh) {
    Outcome cell = alternate() ? new SingleAllocation(counter) : STATIC_SINGLETON;
    bh.consume(cell.value());
  }

  @Benchmark
  public void mergeWithNull(Blackhole bh) {
    SingleAllocation cell = alternate() ? new SingleAllocation(counter) : null;
    bh.consume(cell == null ? 0 : cell.value());
  }

  @Benchmark
  public void flagOnOneAllocation(Blackhole bh) {
    FlaggedAllocation cell = new FlaggedAllocation(alternate(), counter);
    bh.consume(cell.value());
  }

  @Benchmark
  public void closedInFinally(Blackhole bh) {
    SingleAllocation cell = new SingleAllocation(counter++);
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
    SingleAllocation cell = new SingleAllocation(counter++);
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
  public void mergeWithStaticClosedInFinally(Blackhole bh) {
    Outcome cell = alternate() ? new SingleAllocation(counter) : STATIC_SINGLETON;
    try {
      bh.consume(cell.value());
    } finally {
      cell.close();
    }
  }

  /** The single-site shape, whole: one allocation carrying a flag, under try/finally. */
  @Benchmark
  public void flagOnOneAllocationClosedInFinally(Blackhole bh) {
    FlaggedAllocation cell = new FlaggedAllocation(alternate(), counter);
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
    FlaggedAllocation cell = new FlaggedAllocation(alternate(), counter);
    bh.consume(INLINED.apply(cell));
  }

  /**
   * The same, with only the inlining taken away. Whatever this costs is what the discipline buys.
   */
  @Benchmark
  public void passedToUninlinedStrategy(Blackhole bh) {
    FlaggedAllocation cell = new FlaggedAllocation(alternate(), counter);
    bh.consume(UNINLINED.apply(cell));
  }
}
