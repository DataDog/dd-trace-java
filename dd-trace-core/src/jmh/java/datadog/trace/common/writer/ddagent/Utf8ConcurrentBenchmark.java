package datadog.trace.common.writer.ddagent;

import static datadog.trace.common.writer.ddagent.Utf8Workload.NUM_LOOKUPS;
import static datadog.trace.common.writer.ddagent.Utf8Workload.nextTag;
import static datadog.trace.common.writer.ddagent.Utf8Workload.nextValue;

import datadog.communication.serialization.GenerationalUtf8Cache;
import datadog.communication.serialization.SimpleUtf8Cache;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Group;
import org.openjdk.jmh.annotations.GroupThreads;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Multi-threaded companion to {@link Utf8Benchmark}, exercising the caches' intended thread-safety
 * contract against the shared caches.
 *
 * <p>The point of this variant is to illustrate how {@code recalibrate()} is driven under
 * concurrency. Unlike the single-threaded case — where the lone serializer thread recalibrates
 * inline — you would <em>not</em> have every worker recalibrate (that needlessly churns the shared
 * cache and widens the lookup-then-read race window). Instead a single dedicated thread drives
 * {@code recalibrate()} while the remaining threads perform lookups. JMH's {@code @Group} /
 * {@code @GroupThreads} expresses exactly that: 7 lookup threads + 1 recalibrate thread on the same
 * cache.
 *
 * <p>The recalibrate thread runs continuously, which is deliberately more aggressive than a real
 * periodic cadence — it maximizes contention so this doubles as a concurrency guardrail.
 */
@BenchmarkMode(Mode.Throughput)
@State(Scope.Group)
public class Utf8ConcurrentBenchmark {
  static final GenerationalUtf8Cache VALUE_CACHE = new GenerationalUtf8Cache(64, 128);
  static final SimpleUtf8Cache SIMPLE_VALUE_CACHE = new SimpleUtf8Cache(128);

  @Benchmark
  @Group("generational")
  @GroupThreads(7)
  public void generational_lookup(Blackhole bh) {
    for (int i = 0; i < NUM_LOOKUPS; ++i) {
      String tag = nextTag();
      bh.consume(VALUE_CACHE.getUtf8(nextValue(tag)));
    }
  }

  @Benchmark
  @Group("generational")
  @GroupThreads(1)
  public void generational_recalibrate() {
    VALUE_CACHE.recalibrate();
  }

  @Benchmark
  @Group("simple")
  @GroupThreads(7)
  public void simple_lookup(Blackhole bh) {
    for (int i = 0; i < NUM_LOOKUPS; ++i) {
      String tag = nextTag();
      bh.consume(SIMPLE_VALUE_CACHE.getUtf8(nextValue(tag)));
    }
  }

  @Benchmark
  @Group("simple")
  @GroupThreads(1)
  public void simple_recalibrate() {
    SIMPLE_VALUE_CACHE.recalibrate();
  }
}
