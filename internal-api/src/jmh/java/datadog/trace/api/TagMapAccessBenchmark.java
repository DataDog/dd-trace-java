package datadog.trace.api;

import datadog.trace.util.BenchmarkUtils;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Throughput microbenchmark for the core {@link TagMap} access paths — insert (direct, via Ledger,
 * and HashMap variants), raw-value read, and Entry read — over a representative HTTP-server-ish tag
 * set.
 *
 * <p><b>Threading correctness.</b> Runs at {@code @Threads(8)}. All <i>shared</i> state is
 * immutable ({@link #NAMES}/{@link #VALUES}); every bit of <i>mutable</i> state lives in a
 * {@code @State(Scope.Thread)} holder so threads never contend on a shared map, index, or reader
 * flyweight. Earlier TagMap benchmarks shared a cross-thread counter/index, which turned the result
 * into a contention measurement rather than a TagMap measurement — this layout avoids that. Indices
 * are plain per-invocation locals.
 *
 * <p>Run configuration is baked into annotations rather than relying on {@code -Pjmh.*} flags
 * (which the {@code me.champeau.jmh} plugin ignores).
 *
 * <p><b>Key findings (MacBook M1, 8 threads, Java 17):</b>
 *
 * <ul>
 *   <li><b>get</b>: TagMap ({@code getObject}/{@code getEntry} ~96M ops/s) is essentially on par
 *       with HashMap — the slight difference is noise.
 *   <li><b>insert</b>: Direct {@code HashMap} put (65M) is faster than {@code TagMap} (52M) for
 *       plain insertion. However, if a builder pattern is required, {@code TagMap.Ledger} (41M)
 *       handily beats {@code HashMap} builder style — staging map + defensive copy (28M) — because
 *       it avoids the second allocation and second fill pass.
 *   <li><b>clone</b>: See {@link datadog.trace.util.SingleThreadedMapBenchmark} — TagMap clone is
 *       ~4.6x faster than HashMap clone (295M vs 64M ops/s), which dominates span lifecycle costs.
 * </ul>
 *
 * <code>
 * MacBook M1 with 8 threads (Java 17)
 *
 * Benchmark                                           Mode  Cnt         Score         Error  Units
 * TagMapAccessBenchmark.getEntry                     thrpt    5  95559437.524 ± 1381678.908  ops/s
 * TagMapAccessBenchmark.getObject                    thrpt    5  95980166.452 ± 2217719.560  ops/s
 * TagMapAccessBenchmark.insert                       thrpt    5  52523529.023 ± 1816998.150  ops/s
 * TagMapAccessBenchmark.insert_hashMap               thrpt    5  65344306.574 ± 4013136.530  ops/s
 * TagMapAccessBenchmark.insert_hashMap_builderStyle  thrpt    5  28057827.189 ± 1359655.664  ops/s
 * TagMapAccessBenchmark.insert_via_ledger            thrpt    5  41169656.095 ±  773264.754  ops/s
 * </code>
 *
 * <p>Rerun on the same machine and JDK (Zulu 17.0.7, {@code @Fork(2)}, {@code @Threads(8)}) with a
 * new top-level {@code @Setup(Level.Trial)} calling {@link BenchmarkUtils#warmUpHashDispatch} (this
 * file had none before):
 *
 * <pre>{@code
 * Benchmark                      Score (M ops/s)    Error
 * getEntry                              97.00      ± 2.37
 * getObject                             97.70      ± 0.88
 * insert                                52.67      ± 1.28
 * insert_hashMap                        69.57      ± 0.65
 * insert_hashMap_builderStyle           29.51      ± 0.50
 * insert_via_ledger                     37.15      ± 0.78
 * }</pre>
 *
 * <p>Pollution left these paths materially unchanged, but not for the same reason on both sides.
 * {@code TagMap} is typed to {@code String} keys throughout ({@link TagMap#getEntry(String)},
 * {@link TagMap.Ledger#set(String, Object)}) and compares against a {@code String}-declared field,
 * so it has no {@code Object}-typed dispatch site to pollute — it is insulated by construction,
 * whatever the JIT decides. {@code HashMap} does have such sites: {@code getNode} calls {@code
 * hashCode()}/{@code equals()} on an {@code Object}-declared key. Erasure makes that one bytecode
 * index serve every map in the application, so in production it sees many receiver types and is
 * genuinely megamorphic — which is the condition {@code warmUpHashDispatch} reproduces here. Even
 * so, {@code get}/{@code put} inline into a caller holding a statically exact {@code String}, so C2
 * sharpens the argument and devirtualizes without consulting that profile. That is a realistic
 * condition rather than an artifact — plenty of production call sites do hand HashMap a statically
 * known key type, and it legitimately gets that benefit. Where the key type is not deducible, the
 * megamorphic profile governs HashMap and still does not reach TagMap.
 *
 * <p>Consistent with that, most entries move by a couple of percent with overlapping intervals, and
 * the larger moves go up, which pollution cannot cause.
 *
 * <p>The exception is {@code insert_via_ledger}, down about 10% (41.17 to 37.15) with
 * non-overlapping intervals. That is the most allocation-heavy path measured, and {@code
 * warmUpHashDispatch} itself allocates heavily before measurement starts, so setup churn shifting
 * GC state for the trial is a likelier cause than dispatch. Not investigated further.
 *
 * <p>Both tables are the same machine and JDK, but the baseline above ran at {@code Cnt 5} against
 * {@code Cnt 10} here, and on a different day, so small differences carry no weight.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(2)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Threads(8)
@State(Scope.Benchmark)
public class TagMapAccessBenchmark {
  // a representative HTTP-server-ish tag set (immutable -> safe to share across threads)
  static final String[] NAMES = {
    "http.request.method",
    "http.response.status_code",
    "http.route",
    "url.path",
    "url.scheme",
    "server.address",
    "server.port",
    "client.address",
    "network.protocol.version",
    "user_agent.original",
    "span.kind",
    "component",
    "language",
    "error",
    "resource.name",
    "service.name",
    "operation.name",
    "env",
  };

  static final Object[] VALUES = new Object[NAMES.length];

  static {
    for (int i = 0; i < NAMES.length; ++i) {
      VALUES[i] = "value-" + i;
    }
  }

  @Setup(Level.Trial)
  public void setUp(Blackhole bh) {
    BenchmarkUtils.warmUpHashDispatch(bh);
  }

  /**
   * Pre-populated read map, PER-THREAD ({@code Scope.Thread}): each thread owns its own map so
   * reads don't contend on shared mutable state under {@code @Threads(8)}.
   */
  @State(Scope.Thread)
  public static class ReadMap {
    TagMap map;

    @Setup(Level.Trial)
    public void build() {
      this.map = TagMap.create();
      for (int i = 0; i < NAMES.length; ++i) {
        this.map.set(NAMES[i], VALUES[i]);
      }
    }
  }

  @Benchmark
  public TagMap insert() {
    TagMap map = TagMap.create();
    for (int i = 0; i < NAMES.length; ++i) {
      map.set(NAMES[i], VALUES[i]);
    }
    return map;
  }

  @Benchmark
  public TagMap insert_via_ledger() {
    TagMap.Ledger ledger = TagMap.ledger();
    for (int i = 0; i < NAMES.length; ++i) {
      ledger.set(NAMES[i], VALUES[i]);
    }
    return ledger.build();
  }

  @Benchmark
  public Map<String, Object> insert_hashMap() {
    HashMap<String, Object> map = new HashMap<>();
    for (int i = 0; i < NAMES.length; ++i) {
      map.put(NAMES[i], VALUES[i]);
    }
    return map;
  }

  /**
   * Models the builder idiom for HashMap: accumulate into a staging map, then defensively copy. Two
   * allocations, two fill passes — the honest cost of a HashMap-based builder pattern.
   */
  @Benchmark
  public Map<String, Object> insert_hashMap_builderStyle() {
    HashMap<String, Object> staging = new HashMap<>();
    for (int i = 0; i < NAMES.length; ++i) {
      staging.put(NAMES[i], VALUES[i]);
    }
    return new HashMap<>(staging);
  }

  @Benchmark
  public void getObject(ReadMap rm, Blackhole bh) {
    for (int i = 0; i < NAMES.length; ++i) {
      bh.consume(rm.map.getObject(NAMES[i]));
    }
  }

  @Benchmark
  public void getEntry(ReadMap rm, Blackhole bh) {
    for (int i = 0; i < NAMES.length; ++i) {
      bh.consume(rm.map.getEntry(NAMES[i]).objectValue());
    }
  }
}
