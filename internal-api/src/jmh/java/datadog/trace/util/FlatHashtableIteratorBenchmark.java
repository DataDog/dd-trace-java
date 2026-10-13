package datadog.trace.util;

import java.util.Iterator;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Tests the static-polymorphism iterator specialization: walking a shared-hash bucket through the
 * <b>general</b> {@code iterator(table, hash, hashStrategy)} (strategy held in a field -> {@code
 * hashOf} dispatched via the shared type profile) vs the <b>Entry-specialized</b> {@code
 * iterator(table, hash)} (feeds a constant strategy into the shared traversal template -> {@code
 * hashOf} inlines to {@code entry.hash} by constant type-flow). Both return {@code Iterator<E>} and
 * reuse the same core; only the strategy call's dispatch differs.
 *
 * <p><b>Why the general path is fragile.</b> When only one strategy type flows through it, C2
 * devirtualizes {@code hashOf} from the type profile and the two arms tie — type-profile does the
 * specialization's job. But HotSpot keeps <i>one</i> {@code hashOf} profile per bytecode index,
 * shared across every inlining context (no context-sensitive profiling), so <b>{@code setUp}
 * poisons that profile</b> by driving the general iterator with four distinct strategy types. After
 * that, {@code iterate_general} — though it uses a single strategy and looks monomorphic — pays a
 * virtual {@code hashOf} per entry, degraded by callers it has nothing to do with. {@code
 * iterate_specialized} is immune: the constant gives exact-type devirt regardless of the profile.
 * That contrast is the point — the specialization turns a profile-dependent inline into a
 * structural one. Confirm with {@code -XX:+PrintInlining}.
 */
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 3, time = 1)
@Threads(1)
@State(Scope.Thread)
public class FlatHashtableIteratorBenchmark {
  // All entries share this hash -> one contiguous probe-run "bucket" the iterator walks.
  static final long BUCKET_HASH = 0x123456789ABCDEF0L;
  static final int BUCKET_SIZE = 16;

  static final class ItEntry extends FlatHashtable.Entry {
    final int value;

    ItEntry(long hash, int value) {
      super(hash);
      this.value = value;
    }
  }

  // Four DISTINCT hashOf implementors, all reading entry.hash (identical behavior, different
  // types).
  // Loading them defeats CHA; driving all four through the shared traversal in setUp poisons the
  // hashOf profile to megamorphic, so type-profile can't cleanly devirtualize it for the general
  // path — the case the Entry-specialized iterator is immune to.
  //
  // Measured (MacBook M1, Zulu 21, F5, poisoned profile): iterate_general 18.06M ±0.18 vs
  // iterate_specialized 37.88M ±0.16 ops/s (2.10x, tight non-overlapping CIs). Unpoisoned, the two
  // tie (~37.8M) — type-profile does the general path's devirt then; the poisoning is what
  // type-profile can't survive and the constant can.
  static final class ItHashStrategyA implements FlatHashtable.HashStrategy<ItEntry> {
    static final ItHashStrategyA INSTANCE = new ItHashStrategyA();

    private ItHashStrategyA() {}

    @Override
    public long hashOf(ItEntry entry) {
      return entry.hash;
    }
  }

  static final class ItHashStrategyB implements FlatHashtable.HashStrategy<ItEntry> {
    static final ItHashStrategyB INSTANCE = new ItHashStrategyB();

    private ItHashStrategyB() {}

    @Override
    public long hashOf(ItEntry entry) {
      return entry.hash;
    }
  }

  static final class ItHashStrategyC implements FlatHashtable.HashStrategy<ItEntry> {
    static final ItHashStrategyC INSTANCE = new ItHashStrategyC();

    private ItHashStrategyC() {}

    @Override
    public long hashOf(ItEntry entry) {
      return entry.hash;
    }
  }

  static final class ItHashStrategyD implements FlatHashtable.HashStrategy<ItEntry> {
    static final ItHashStrategyD INSTANCE = new ItHashStrategyD();

    private ItHashStrategyD() {}

    @Override
    public long hashOf(ItEntry entry) {
      return entry.hash;
    }
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  static final FlatHashtable.HashStrategy<ItEntry>[] STRATEGIES =
      new FlatHashtable.HashStrategy[] {
        ItHashStrategyA.INSTANCE,
        ItHashStrategyB.INSTANCE,
        ItHashStrategyC.INSTANCE,
        ItHashStrategyD.INSTANCE,
      };

  ItEntry[] table;

  // Kept live so the profile-poisoning loop below can't be dead-code-eliminated.
  static long POISON_SINK;

  @Setup(Level.Trial)
  public void setUp() {
    // Sparse so the bucket is one contiguous run with a clean terminating empty slot.
    table = FlatHashtable.create(ItEntry.class, BUCKET_SIZE, FlatHashtable.LOW_LOAD_FACTOR);
    for (int i = 0; i < BUCKET_SIZE; ++i) {
      FlatHashtable.insert(table, new ItEntry(BUCKET_HASH, i)); // all share the hash => one bucket
    }
    // Poison the shared HashIterator.advanceWith hashOf profile: drive the GENERAL iterator with
    // four distinct strategy types, hot enough that C2 records the site as megamorphic. HotSpot
    // keeps one per-bci profile shared across all inlining contexts (no context-sensitive profiling
    // — tried in Zing, never robust), so those unrelated callers permanently pollute the profile.
    long sink = 0;
    for (int r = 0; r < 200_000; ++r) {
      sink += poison();
    }
    POISON_SINK = sink;
  }

  /** Drives the general iterator once per distinct strategy type — the profile poisoner. */
  private long poison() {
    long sink = 0;
    for (FlatHashtable.HashStrategy<ItEntry> s : STRATEGIES) {
      Iterator<ItEntry> it = FlatHashtable.iterator(table, BUCKET_HASH, s);
      while (it.hasNext()) {
        sink += it.next().value;
      }
    }
    return sink;
  }

  /**
   * General iterator with a SINGLE strategy — looks monomorphic, but its {@code hashOf} still
   * dispatches virtually because {@code setUp} already poisoned the shared profile with other
   * strategy types. This is the cross-caller pollution failure mode: a caller degraded by callers
   * it has nothing to do with. (Unpoisoned, this ties {@code iterate_specialized} — type-profile
   * then devirtualizes it; the poisoning is what type-profile can't survive and the constant can.)
   */
  @Benchmark
  public long iterate_general() {
    long sum = 0;
    Iterator<ItEntry> it = FlatHashtable.iterator(table, BUCKET_HASH, ItHashStrategyA.INSTANCE);
    while (it.hasNext()) {
      sum += it.next().value;
    }
    return sum;
  }

  /**
   * Entry-specialized iterator: feeds the constant Entry-hash strategy into the shared template, so
   * {@code hashOf} inlines to {@code entry.hash} <b>structurally</b> (constant type-flow, not
   * profile) — immune to the poisoned profile that sinks {@code iterate_general}.
   */
  @Benchmark
  public long iterate_specialized() {
    long sum = 0;
    Iterator<ItEntry> it = FlatHashtable.iterator(table, BUCKET_HASH); // Entry overload
    while (it.hasNext()) {
      sum += it.next().value; // hashOf inlined to entry.hash via the constant strategy
    }
    return sum;
  }
}
