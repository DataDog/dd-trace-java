package datadog.trace.util;

import java.util.function.BiConsumer;
import java.util.function.ObjLongConsumer;
import javax.annotation.Nullable;
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
 * A do/don't guide for using {@link Maybe}, not a research instrument like {@code
 * datadog.trace.util.escape.EscapeShapeBenchmark} (which this class's arms are built on top of).
 * Read {@code gc.alloc.rate.norm} — the "good" arm in each pair is expected to read 0 B/op on every
 * JDK the way {@code EscapeShapeBenchmark}'s {@code singleSite}/{@code passedToInlinedStrategy}
 * arms do; the paired "bad" arm exists to make the regression visible rather than theoretical. Run
 * as
 *
 * <pre>./gradlew :internal-api:jmh -Pjmh.includes=TryUsagePatterns -Pjmh.profilers=gc -PtestJvm=17
 * </pre>
 *
 * This is the intended backing example for a perf-review check like "EA-dependent elision on a hot
 * path where a structural alternative exists at parity → prefer the deterministic form"
 * (APMLP-1799's J12 note): both pairs below have a same-cost deterministic form available, so
 * reviewing a real diff against these arms is a matter of asking "which arm does this call site
 * look like," not re-deriving the escape-analysis argument each time.
 *
 * <p><b>The boxed-context pair is the sharper illustration of that J12 phrase than it first looks
 * like.</b> {@code badBoxedContextUpdateInlined} was expected to allocate the boxed {@code Long}
 * and, measured here, does not -- with the whole {@code update} call inlined, C2 scalar-replaces
 * the box the same as it would any other short-lived object. That is exactly the "EA-dependent"
 * half of J12's phrase: {@link Maybe#update(long, ObjLongConsumer)} has no box to eliminate in the
 * first place, so it reads 0 B/op *regardless* of whether this call site keeps inlining; the
 * generic-context form's 0 B/op is contingent on inlining holding, which {@code
 * badBoxedContextUpdateUninlined} demonstrates by taking that away via the same {@code
 * -XX:CompileCommand=dontinline} technique {@code EscapeShapeBenchmark} uses for its {@code
 * UninlinedStrategy} arm.
 */
@Fork(
    value = 2,
    jvmArgsAppend = {
      "-XX:CompileCommand=dontinline,datadog.trace.util.MaybeUsagePatternsBenchmark$UninlinedBoxedAdder::accept"
    })
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(java.util.concurrent.TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class MaybeUsagePatternsBenchmark {

  static final class Widget {
    long count;
  }

  /** A non-capturing updater, as {@link Maybe#update(long, ObjLongConsumer)} expects. */
  static final ObjLongConsumer<Widget> ADD_PRIMITIVE = (w, delta) -> w.count += delta;

  /**
   * The same update expressed through the generic-context overload instead. {@code Long} is not
   * assignable from {@code long} without boxing, so calling {@link Maybe#update(Object,
   * BiConsumer)} with a {@code long} argument boxes it every time — the exact per-call allocation
   * {@link Maybe#update(long, ObjLongConsumer)} exists to avoid. Kept as a {@code BiConsumer<Long,
   * Widget>} rather than inlined at the call site so the two arms below differ only in which
   * overload is selected, not in lambda shape.
   */
  static final BiConsumer<Long, Widget> ADD_BOXED = (delta, w) -> w.count += delta;

  /**
   * Same logic as {@link #ADD_BOXED}, but as a named class rather than a lambda so {@code
   * -XX:CompileCommand=dontinline} (see this class's {@link Fork} annotation) has a concrete method
   * to target -- kept out of line the same way {@code EscapeShapeBenchmark}'s {@code
   * UninlinedStrategy} is, by the {@code CompileCommand} rather than {@code CompilerControl}, since
   * JMH's processor only reads that annotation from {@code @Benchmark} methods.
   */
  static final class UninlinedBoxedAdder implements BiConsumer<Long, Widget> {
    @Override
    public void accept(Long delta, Widget w) {
      w.count += delta;
    }
  }

  static final BiConsumer<Long, Widget> ADD_BOXED_UNINLINED = new UninlinedBoxedAdder();

  /**
   * Deliberately outside {@code Long}'s [-128, 127] cache range -- a cached delta like {@code 1L}
   * would make {@link #badBoxedContextUpdateUninlined} read 0 B/op too, for a reason with nothing
   * to do with which overload got picked.
   */
  static final long DELTA = 1_000L;

  private final Widget[] table = new Widget[8];
  private int counter;

  public MaybeUsagePatternsBenchmark() {
    for (int i = 0; i < table.length; i++) {
      // Half the slots stay null so every arm below actually exercises the refused/empty path,
      // not just the present one -- see EscapeShapeBenchmark's `alternate()` javadoc for why an
      // always-taken branch would quietly turn these into single-site arms and lie.
      if ((i & 1) == 0) {
        table[i] = new Widget();
      }
    }
  }

  private int nextKey() {
    return (counter++) & (table.length - 1);
  }

  @Nullable
  private Widget lookup(int key) {
    return table[key];
  }

  /**
   * GOOD: exactly one {@code Maybe.of(...)} call site, fed by delegating to the existing nullable
   * method. See {@link Maybe}'s class javadoc for why this is the recommended shape.
   */
  private Maybe<Widget> tryLookupDelegating(int key) {
    return Maybe.of(lookup(key));
  }

  /**
   * BAD: a {@code Maybe.of(...)} call site per branch. Both branches return the same wrapper type,
   * so this looks equivalent to {@link #tryLookupDelegating} at every call site that uses it — the
   * difference only shows up here, in the allocation profile of the method that builds the {@code
   * Maybe}, which is exactly why it is easy to introduce by accident.
   */
  private Maybe<Widget> tryLookupMultiSite(int key) {
    Widget w = lookup(key);
    if (w != null) {
      return Maybe.of(w);
    } else {
      return Maybe.<Widget>of(null);
    }
  }

  @Benchmark
  public void goodSingleConstructionSite(Blackhole bh) {
    Maybe<Widget> t = tryLookupDelegating(nextKey());
    bh.consume(t.isPresent());
  }

  @Benchmark
  public void badMultiConstructionSite(Blackhole bh) {
    Maybe<Widget> t = tryLookupMultiSite(nextKey());
    bh.consume(t.isPresent());
  }

  @Benchmark
  public void goodPrimitiveContextUpdate(Blackhole bh) {
    Maybe<Widget> t = tryLookupDelegating(nextKey());
    t.update(DELTA, ADD_PRIMITIVE);
    bh.consume(t.isPresent());
  }

  /**
   * Reads 0 B/op here despite boxing {@link #DELTA} on every call -- this call site stays inlined,
   * so C2 scalar-replaces the {@code Long} the same as any other non-escaping object. See {@link
   * #badBoxedContextUpdateUninlined} for what that 0 is actually contingent on.
   */
  @Benchmark
  public void badBoxedContextUpdateInlined(Blackhole bh) {
    Maybe<Widget> t = tryLookupDelegating(nextKey());
    t.update(DELTA, ADD_BOXED);
    bh.consume(t.isPresent());
  }

  /**
   * The same boxing, with only the inlining taken away (via {@link UninlinedBoxedAdder} and this
   * class's {@code CompileCommand}). Whatever this costs above {@link #goodPrimitiveContextUpdate}
   * is the box {@link #badBoxedContextUpdateInlined} was quietly relying on EA to remove.
   */
  @Benchmark
  public void badBoxedContextUpdateUninlined(Blackhole bh) {
    Maybe<Widget> t = tryLookupDelegating(nextKey());
    t.update(DELTA, ADD_BOXED_UNINLINED);
    bh.consume(t.isPresent());
  }
}
