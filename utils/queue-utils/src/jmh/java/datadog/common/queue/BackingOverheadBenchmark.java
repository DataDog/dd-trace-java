package datadog.common.queue;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.jctools.queues.MpmcArrayQueue;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
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
 * The two structures the multi-consumer backing could sit on, priced against each other. This is
 * the measurement {@link MpmcWorkQueue} chose the array ring on.
 *
 * <pre>./gradlew :utils:queue-utils:jmh -Pjmh.includes=BackingOverhead -Pjmh.profilers=gc</pre>
 *
 * <p>The element is a preallocated singleton in every arm, so the only allocation measured is the
 * structure's own — which is the point, because the linked queue builds a node per element and the
 * ring builds nothing.
 *
 * <pre>
 * Benchmark          threads    ns/op   B/op
 * mpmcSteady               1     12.7      0
 * clqSteady                1     20.6     24
 * mpmcContended            4    559       0
 * clqContended             4    625      24
 * </pre>
 *
 * <p>Uncontended the ring is about 38% cheaper and stops manufacturing 24 bytes of garbage per
 * element, which at a million elements a second is 24MB/s the linked queue creates and the ring
 * does not. The four-thread arms are ±287 and ±540 respectively and say nothing: each thread offers
 * and polls the same queue, so both ends of it are thrashed and the measurement is of the harness.
 * They are kept because leaving them out would imply the contended case was measured and settled.
 *
 * <p>What the ring costs in exchange is that it is not linearizable — see {@link MpmcWorkQueue} for
 * why claiming a place first makes that survivable, and {@link Queues#mpmcArrayQueue} for why it
 * would not be otherwise.
 */
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class BackingOverheadBenchmark {

  private static final int CAPACITY = 1024;
  private static final String ELEMENT = "element";

  private Queue<String> clq1;
  private Queue<String> mpmc1;
  private Queue<String> clqN;
  private Queue<String> mpmcN;

  @Setup
  public void setUp() {
    clq1 = new ConcurrentLinkedQueue<>();
    mpmc1 = new MpmcArrayQueue<>(CAPACITY);
    clqN = new ConcurrentLinkedQueue<>();
    mpmcN = new MpmcArrayQueue<>(CAPACITY);
  }

  @Benchmark
  @Threads(1)
  public void clqSteady(Blackhole bh) {
    bh.consume(clq1.offer(ELEMENT));
    bh.consume(clq1.poll());
  }

  @Benchmark
  @Threads(1)
  public void mpmcSteady(Blackhole bh) {
    bh.consume(mpmc1.offer(ELEMENT));
    bh.consume(mpmc1.poll());
  }

  @Benchmark
  @Threads(4)
  public void clqContended(Blackhole bh) {
    bh.consume(clqN.offer(ELEMENT));
    bh.consume(clqN.poll());
  }

  @Benchmark
  @Threads(4)
  public void mpmcContended(Blackhole bh) {
    bh.consume(mpmcN.offer(ELEMENT));
    bh.consume(mpmcN.poll());
  }
}
