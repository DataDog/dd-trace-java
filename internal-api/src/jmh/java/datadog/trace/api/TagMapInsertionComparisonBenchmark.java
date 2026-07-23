package datadog.trace.api;

import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import java.util.HashMap;
import java.util.Map;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Insertion comparison for the deck's "how do we do vs HashMap / TagMap 1.0" and "id vs name"
 * claims (slides 3 / 7 / 8). Same tag count, four ways:
 *
 * <ul>
 *   <li><b>hashMap</b> — {@code HashMap.put(name, value)}. The baseline every ratio is quoted
 *       against (so slides reconcile across runs).
 *   <li><b>tagMapByName</b> — {@code TagMap.set(name, value)} with known names: {@code keyOf} hit +
 *       dense store. This is the 2.0 name path. (Run on <i>master</i>, the same arm is true 1.0 —
 *       master's {@code set(name)} has no {@code keyOf} — so master-vs-branch on this arm, both
 *       normalized to hashMap, is the 2.0-vs-1.0 comparison.)
 *   <li><b>tagMapById</b> — {@code TagMap.set(id, value)} with pre-resolved {@code KnownTags} ids:
 *       dense store, NO {@code keyOf}. The 2.0 id path. tagMapById-vs-tagMapByName is the {@code
 *       keyOf} tax that instrumentation recovers by migrating to ids (slide 7/8).
 *   <li><b>tagMapCustom</b> — {@code set(customName, value)}: {@code keyOf} miss + bucket + Entry
 *       (the unknown/custom-tag path).
 * </ul>
 *
 * <p>Run with {@code -prof gc} for the allocation columns (deterministic). The throughput columns
 * are thermal-fragile — quote them only from a quiet machine, and take the id-vs-name and
 * vs-HashMap ratios rather than absolute ops/s. True 1.0 (no {@code keyOf}) is not on this branch;
 * see {@code tagMapByName} above for the master-run recipe.
 *
 * <p><b>Results — WITH the bloom-filter fast-path (dense-store → bloom → id stack), JDK 17 (Zulu
 * 17.0.7, Apple Silicon, idle box), {@code -prof gc -f 5 -wi 5 -i 5}, 2026-07-09.</b> Alloc is
 * deterministic (quotable); throughput was measured on a quiet box (25 iters, tight error bars),
 * trustworthy for the ratios — except {@code tagMapById@7} carries ~6% fork-to-fork variance (bloom
 * fast-path inlining nondeterminism; check PrintInlining before quoting 0.99x as hard parity).
 *
 * <pre>{@code
 *                alloc B/op (7 / 12)    thrpt vs hashMap (7 / 12)
 * hashMap          352 / 512            1.00x / 1.00x
 * tagMapById       184 / 408            0.99x / 0.63x
 * tagMapByName     184 / 408            0.66x / 0.50x
 * tagMapCustom     416 / 712            0.59x / 0.46x
 * }</pre>
 *
 * <p>Three takeaways. (1) <b>id and name insertion allocate identically</b> (184/408) — the
 * id-vs-name advantage is CPU (skipping {@code keyOf}), not allocation. Dense allocs ~half of
 * HashMap at 7 tags (~20% less at 12) and beats the bucket/Entry path ({@code tagMapCustom})
 * everywhere; the bloom cost +8 B/op (one {@code long} field). (2) <b>the bloom brings id insertion
 * to HashMap parity at typical counts</b> — 0.99x at 7 tags (was 0.91x pre-bloom), 0.63x at 12 (was
 * 0.54x). Not a beat: the crude {@code fieldPos & 63} mapping collides more as tags grow, so some
 * appends still scan; per-type graph-coloring is the lever to push 12 toward parity. (3) <b>id
 * clearly beats the bucket/1.0 path</b> — 1.4–1.7x ({@code tagMapById} vs {@code tagMapCustom});
 * pin exact 2.0-vs-1.0 with a master run (true 1.0 has no {@code keyOf}).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(3)
@Threads(8)
public class TagMapInsertionComparisonBenchmark {

  // A realistic web/db span's known tag set (same list as DenseStoreAllocBenchmark).
  static final String[] KNOWN =
      new String[] {
        DDTags.BASE_SERVICE,
        Tags.VERSION,
        Tags.COMPONENT,
        Tags.SPAN_KIND,
        Tags.HTTP_METHOD,
        Tags.HTTP_ROUTE,
        Tags.DB_TYPE,
        Tags.DB_INSTANCE,
        Tags.PEER_HOSTNAME,
        Tags.DB_USER,
        DDTags.LANGUAGE_TAG_KEY,
        Tags.PEER_PORT,
      };

  @Param({"7", "12"})
  int tagCount;

  private String[] knownNames;
  private long[] knownIds;
  private String[] customNames;
  private String[] values;

  @Setup(Level.Trial)
  public void setup() {
    KnownTags.init(); // register the real (allocation-free) resolver
    this.knownNames = new String[tagCount];
    this.knownIds = new long[tagCount];
    this.customNames = new String[tagCount];
    this.values = new String[tagCount];
    for (int i = 0; i < tagCount; i++) {
      this.knownNames[i] = KNOWN[i];
      this.knownIds[i] =
          KnownTagCodec.keyOf(KNOWN[i]); // resolve name -> id once (as codegen would)
      this.customNames[i] = "custom.tag." + i;
      this.values[i] = "value-" + i;
    }
  }

  @Benchmark
  public Map<String, Object> hashMap() {
    final Map<String, Object> m = new HashMap<>(16);
    for (int i = 0; i < tagCount; i++) {
      m.put(knownNames[i], values[i]);
    }
    return m;
  }

  @Benchmark
  public TagMap tagMapByName() {
    final TagMap m = TagMap.create(16);
    for (int i = 0; i < tagCount; i++) {
      m.set(knownNames[i], values[i]);
    }
    return m;
  }

  @Benchmark
  public TagMap tagMapById() {
    final TagMap m = TagMap.create(16);
    for (int i = 0; i < tagCount; i++) {
      m.set(knownIds[i], values[i]);
    }
    return m;
  }

  @Benchmark
  public TagMap tagMapCustom() {
    final TagMap m = TagMap.create(16);
    for (int i = 0; i < tagCount; i++) {
      m.set(customNames[i], values[i]);
    }
    return m;
  }
}
