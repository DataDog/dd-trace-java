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
 * <p>Java 17 results (Apple M1, {@code @Fork(2)}, {@code @Threads(8)}) with the front-loaded {@link
 * BenchmarkUtils#warmUpHashDispatch} pollution design (M ops/s = millions):
 *
 * <pre>{@code
 * contains_hashSet            1376.4
 * contains_synchronizedSet     814.5    (~41% slower — the uncontended sync tax)
 * iterate_hashSet               94.6
 * iterate_synchronizedSet       91.3    (one monitor acquire amortized over the walk)
 *
 * create_hashSet          94.4   clone_hashSet          48.4
 * create_hashSet_sized    95.9   clone_synchronizedSet  44.0
 * create_linkedHashSet    41.3   clone_linkedHashSet    42.7
 * create_synchronizedSet  41.7   clone_treeSet          86.1
 * create_treeSet          32.7
 * }</pre>
 *
 * <p>Key findings:
 *
 * <ul>
 *   <li><b>Uncontended synchronization tax</b> holds up under pollution: {@code contains} is ~41%
 *       slower synchronized (1376.4 → 814.5M ops/s), consistent with Java 15+'s biased locking
 *       being disabled by default (JEP 374). {@code iterate}'s tax stays small (~3.5%): one monitor
 *       acquire amortized over the walk.
 *   <li>{@code TreeSet} stays the slowest structure to build but is, notably, the fastest to clone
 *       (86.1M) — worth a closer look if that gap turns out to matter elsewhere.
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

  // Front-load pollution once per trial, entirely before JMH's warmup starts: JMH
  // injects the Blackhole straight into this setup method, so no per-benchmark
  // scratch state is needed.
  @Setup(Level.Trial)
  public void setUp(Blackhole bh) {
    BenchmarkUtils.warmUpHashDispatch(bh);

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
