package datadog.trace.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Single-threaded (uncontended) set usage: each thread builds, reads, and discards its <i>own</i>
 * sets. Per-thread state ({@link Scope#Thread}); mirrors {@link SingleThreadedMapBenchmark} on the
 * set side. Running at {@code @Threads(8)} keeps allocation / GC interactions visible without lock
 * contention.
 *
 * <p>Sets in the tracer skew read-only/fixed (see {@link ImmutableSetBenchmark}); this covers the
 * mutable-lifecycle case for completeness and — via {@link Collections#synchronizedSet} — the
 * <i>uncontended</i> synchronization tax. Because each thread owns its synchronized set, the
 * monitor is only ever locked by one thread: biased locking ≈ free on Java ≤ 11, full uncontended
 * CAS on Java 15+ (biased locking disabled by default, JEP 374). The unsynchronized {@code hashSet}
 * {@code contains}/{@code iterate} methods are the in-harness baseline; the tax is the delta.
 *
 * <p>Java 17 results (Apple M1, {@code @Fork(2)}, {@code @Threads(8)}; M ops/s = millions):
 *
 * <pre>{@code
 * contains_hashSet            1291
 * contains_synchronizedSet     808    (~37% slower — the uncontended sync tax)
 * iterate_hashSet              91
 * iterate_synchronizedSet      90    (one monitor acquire amortized over the walk)
 *
 * create_hashSet         81    clone_hashSet          48
 * create_hashSet_sized   78    clone_synchronizedSet  47
 * create_linkedHashSet   61    clone_linkedHashSet    59
 * create_synchronizedSet 41    clone_treeSet          83
 * create_treeSet         36
 * }</pre>
 *
 * <p>Key findings:
 *
 * <ul>
 *   <li><b>Uncontended synchronization tax</b> on {@code contains} is ~37% (1291 → 808M ops/s) even
 *       with no contention and biased locking disabled (Java 17, JEP 374) — the full per-lock CAS
 *       cost. On {@code iterate} it nearly vanishes: a single monitor acquire amortized over the
 *       traversal.
 *   <li>Construction: {@code TreeSet} is the slowest to build (~36M); the {@code synchronizedSet}
 *       wrapper adds a modest cost over plain {@code HashSet}. (Allocation-path numbers carry more
 *       run-to-run variance than the read paths.)
 * </ul>
 */
@Fork(2)
@Warmup(iterations = 2)
@Measurement(iterations = 3)
@Threads(8)
@State(Scope.Thread)
public class SingleThreadedSetBenchmark {
  static final String[] ELEMENTS = {
    "foo", "bar", "baz", "quux", "hello", "world",
    "service", "queryString", "lorem", "ipsum", "dolem", "sit"
  };

  // Distinct String instances so lookups exercise equals(), not identity.
  static final String[] EQUAL_ELEMENTS = newEqualElements();

  static String[] newEqualElements() {
    String[] copies = new String[ELEMENTS.length];
    for (int i = 0; i < ELEMENTS.length; ++i) {
      copies[i] = new String(ELEMENTS[i]);
    }
    return copies;
  }

  static void fill(Set<String> set) {
    for (String s : ELEMENTS) {
      set.add(s);
    }
  }

  // Per-thread prebuilt sets for the read + clone benchmarks (built once per trial, per thread).
  HashSet<String> hashSet;
  Set<String> synchronizedSet;
  TreeSet<String> treeSet;
  LinkedHashSet<String> linkedHashSet;
  int index = 0;

  @Setup(Level.Trial)
  public void setUp() {
    hashSet = new HashSet<>(Arrays.asList(ELEMENTS));
    synchronizedSet = Collections.synchronizedSet(new HashSet<>(hashSet));
    treeSet = new TreeSet<>(Arrays.asList(ELEMENTS));
    linkedHashSet = new LinkedHashSet<>(Arrays.asList(ELEMENTS));
  }

  String nextLookup() {
    if (++index >= EQUAL_ELEMENTS.length) {
      index = 0;
    }
    return EQUAL_ELEMENTS[index];
  }

  // ---- construction: build cost + allocation ----

  @Benchmark
  public Set<String> create_hashSet() {
    HashSet<String> set = new HashSet<>();
    fill(set);
    return set;
  }

  @Benchmark
  public Set<String> create_hashSet_sized() {
    HashSet<String> set = new HashSet<>(ELEMENTS.length);
    fill(set);
    return set;
  }

  @Benchmark
  public Set<String> create_synchronizedSet() {
    Set<String> set = Collections.synchronizedSet(new HashSet<>());
    fill(set);
    return set;
  }

  @Benchmark
  public Set<String> create_treeSet() {
    TreeSet<String> set = new TreeSet<>();
    fill(set);
    return set;
  }

  @Benchmark
  public Set<String> create_linkedHashSet() {
    LinkedHashSet<String> set = new LinkedHashSet<>();
    fill(set);
    return set;
  }

  // ---- copy ----

  @Benchmark
  public Set<String> clone_hashSet() {
    return new HashSet<>(hashSet);
  }

  @Benchmark
  public Set<String> clone_synchronizedSet() {
    return Collections.synchronizedSet(new HashSet<>(synchronizedSet));
  }

  @Benchmark
  public Set<String> clone_treeSet() {
    return new TreeSet<>(treeSet);
  }

  @Benchmark
  public Set<String> clone_linkedHashSet() {
    return new LinkedHashSet<>(linkedHashSet);
  }

  // ---- read: unsynchronized baseline vs uncontended synchronized (biased-locking story) ----

  @Benchmark
  public boolean contains_hashSet() {
    return hashSet.contains(nextLookup());
  }

  @Benchmark
  public boolean contains_synchronizedSet() {
    return synchronizedSet.contains(nextLookup());
  }

  @Benchmark
  public void iterate_hashSet(Blackhole blackhole) {
    for (String s : hashSet) {
      blackhole.consume(s);
    }
  }

  @Benchmark
  public void iterate_synchronizedSet(Blackhole blackhole) {
    // Collections.synchronizedSet requires the caller to synchronize during iteration; this is the
    // correct usage and measures one (uncontended) monitor acquire around the traversal.
    synchronized (synchronizedSet) {
      for (String s : synchronizedSet) {
        blackhole.consume(s);
      }
    }
  }
}
