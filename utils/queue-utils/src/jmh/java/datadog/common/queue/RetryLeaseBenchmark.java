package datadog.common.queue;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * What the retry lease costs, and whether it costs it only on the failure path.
 *
 * <pre>./gradlew :utils:queue-utils:jmh -Pjmh.includes=RetryLease -Pjmh.profilers=gc</pre>
 *
 * <p>{@code BaseWorkQueue.lease} allocates an anonymous {@link RetryQueue} per failure, capturing
 * the queue and the attempt number. The alternative is one lease per queue held in a field, which
 * allocates never -- but a field cannot carry a per-item attempt number without either a mutable
 * field (a race the moment {@code createMpmcQueue} gives the queue a second consumer) or an API
 * change putting {@code attempt} back into {@link RetryQueue#retry}. The question this answers is
 * whether that trade is even on the table, or whether escape analysis already deletes the
 * allocation.
 *
 * <p>The lease is handed to {@link RetryStrategy#onFailure}, which is user code behind an
 * interface. C2 can only prove the lease does not escape by inlining {@code onFailure} and seeing
 * what it does, so the answer should depend on how many strategy types the process has loaded --
 * hence the {@code strategies} parameter, which loads one, two, or four.
 *
 * <p>{@code gc.alloc.rate.norm} is the number that answers it. The thrown exception is a
 * preallocated, stackless singleton so that its own allocation does not swamp the lease's.
 *
 * <p>Results:
 *
 * <pre>
 * gc.alloc.rate.norm, B/op        ONE   TWO   FOUR      ns/op
 * succeeds                          0     0      0       21.3
 * failsAndGivesUp                   0     0      0       22.1
 * failsAndGivesUpVirtual            0     0     24       22.4
 * failsAndRetries                  24    24     24       41.6
 * failsWithHandler (control)        0     0      0       22.5
 * </pre>
 *
 * <p>JDK 25, {@code -Pjmh.forks=2}. Two separate things are visible, and the first was not the one
 * being looked for.
 *
 * <p>A {@code static final} strategy never pays for the lease, at any number of loaded types.
 * {@code failsAndGivesUp} reads zero at {@code FOUR}, where the receiver profile is thoroughly
 * polluted, because the profile is not what C2 consulted: the field is a constant, so the exact
 * receiver class is known outright and {@code onFailure} devirtualizes by static resolution. {@code
 * failsAndGivesUpVirtual} is the identical strategy behind a non-final field, and that arm does
 * move -- zero while the site stays bimorphic, 24 bytes once it does not. Those 24 bytes are the
 * lease: an object header, the captured queue, and the captured attempt number.
 *
 * <p>{@code failsAndRetries} allocates 24 bytes at every type count, including where the lease is
 * provably free. That is the {@code Retry} wrapper the re-admitted item is boxed in, not the lease,
 * and only an item actually resubmitted pays it. {@code failsWithHandler} takes the same throw down
 * the {@link ExceptionHandler} branch, which is never handed a lease, and reads zero throughout --
 * so the 24 bytes in the virtual arm are the lease rather than something else on the failure path.
 *
 * <p>Timing says nothing either way; every arm that fails once is within noise of every other, and
 * an allocation this size is invisible next to a throw.
 */
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Threads(1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class RetryLeaseBenchmark {

  public enum Strategies {
    ONE,
    TWO,
    FOUR
  }

  private static final String ELEMENT = "element";

  /** Stackless and shared, so the failure path's own allocation is not what gets measured. */
  private static final RuntimeException FAILURE =
      new RuntimeException("consumer failed", null, false, false) {};

  private static final Consumer<String> SUCCEEDS = item -> {};

  private static final Consumer<String> THROWS =
      item -> {
        throw FAILURE;
      };

  /** Gives up without touching the lease: the lease is allocated, passed, and never used. */
  private static final RetryStrategy<String> GIVE_UP = (item, attempt, failure, queue) -> false;

  /** Uses the lease, which is what a strategy is handed one for. */
  private static final RetryStrategy<String> RETRY_ONCE =
      (item, attempt, failure, queue) -> attempt <= 1 && queue.retry(item);

  private static final ExceptionHandler<String> HANDLER = (item, failure) -> {};

  /**
   * The same {@link #GIVE_UP} strategy, reached through a non-final instance field instead of a
   * {@code static final} one. Only the binding differs, and it is the whole experiment: a constant
   * strategy tells C2 the exact receiver type outright, so {@code onFailure} devirtualizes by
   * static resolution no matter how many strategy types the process has loaded. Reading it from a
   * field takes that away and leaves the shared receiver profile as the only evidence C2 has.
   */
  private RetryStrategy<String> bound;

  /** Two more strategy types, loaded to move the {@code onFailure} call site off monomorphic. */
  private static final RetryStrategy<String> THIRD =
      new RetryStrategy<String>() {
        @Override
        public boolean onFailure(String i, int attempt, Throwable f, RetryQueue<String> q) {
          return false;
        }
      };

  private static final RetryStrategy<String> FOURTH =
      new RetryStrategy<String>() {
        @Override
        public boolean onFailure(String i, int attempt, Throwable f, RetryQueue<String> q) {
          return false;
        }
      };

  @Param({"ONE", "TWO", "FOUR"})
  public Strategies strategies;

  private WorkQueue<String> queue;

  private WorkQueue<String> other;

  @Setup
  public void setUp(Blackhole bh) {
    queue = WorkQueues.createMpscQueue(1024);
    bound = GIVE_UP;
    other = WorkQueues.createMpscQueue(1024);
    // Drive the extra strategy types through the same onFailure site the measured loop uses, so
    // the only thing the parameter varies is how many types that site has seen.
    for (int i = 0; i < 50_000; i++) {
      if (strategies != Strategies.ONE) {
        other.tryPut(ELEMENT);
        other.processOrRetry(THROWS, THIRD);
      }
      if (strategies == Strategies.FOUR) {
        other.tryPut(ELEMENT);
        other.processOrRetry(THROWS, FOURTH);
        other.tryPut(ELEMENT);
        other.processOrRetry(THROWS, RETRY_ONCE);
        other.process(bh::consume);
      }
    }
  }

  @Benchmark
  public boolean succeeds() {
    queue.tryPut(ELEMENT);
    return queue.processOrRetry(SUCCEEDS, GIVE_UP);
  }

  @Benchmark
  public boolean failsAndGivesUp() {
    queue.tryPut(ELEMENT);
    return queue.processOrRetry(THROWS, GIVE_UP);
  }

  /** Retried once, then given up on, so the queue is empty again at the end of every invocation. */
  @Benchmark
  public boolean failsAndRetries() {
    queue.tryPut(ELEMENT);
    boolean first = queue.processOrRetry(THROWS, RETRY_ONCE);
    return first & queue.processOrRetry(THROWS, RETRY_ONCE);
  }

  @Benchmark
  public boolean failsAndGivesUpVirtual() {
    queue.tryPut(ELEMENT);
    return queue.processOrRetry(THROWS, bound);
  }

  /** The control: the same throw down the handler branch, which is never handed a lease. */
  @Benchmark
  public boolean failsWithHandler() {
    queue.tryPut(ELEMENT);
    return queue.processOrHandle(THROWS, HANDLER);
  }
}
