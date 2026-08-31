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
 * gc.alloc.rate.norm, B/op          ONE   TWO   FOUR      ns/op
 * succeeds                            0     0      0       21.3
 * failsAndGivesUp (static final)      0     0      0       22.5
 * failsAndGivesUpInline (lambda)      0     0      0       22.6
 * failsAndGivesUpCapturing            0     0      0       21.1
 * failsAndGivesUpVirtual (field)      0     0     24       21.8
 * failsAndRetries                    24    24     24       41.6
 * failsWithHandler (control)          0     0      0       22.5
 * </pre>
 *
 * <p>JDK 25, {@code -Pjmh.forks=2}. Three things are visible, and only the third was the one being
 * looked for.
 *
 * <p><b>What the strategy is bound to decides everything.</b> Four of the five failure arms run the
 * same strategy and differ only in how the call site reaches it. Three of them cost nothing at any
 * number of loaded implementations: a {@code static final} field, which is a trusted constant; an
 * inline non-capturing lambda, whose {@code invokedynamic} links through a {@code ConstantCallSite}
 * and folds to the same thing without a field to declare; and -- the surprise -- an inline
 * <i>capturing</i> lambda, which is not a constant at all but whose allocation site is right there
 * in the caller, so C2 knows the exact type anyway and scalar-replaces the capture. What they share
 * is that C2 knows the receiver's exact class without asking the profile. Only {@code
 * failsAndGivesUpVirtual}, which loads the identical object from a non-final instance field, has to
 * ask -- and at {@code FOUR} the profile has nothing useful to say.
 *
 * <p>So the discipline is not "hoist the strategy into a field"; a plain {@code final} instance
 * field is the one shape here that fails, because HotSpot does not trust it ({@code
 * TrustFinalNonStaticFields} is off) and a human reads it as bound-once regardless. The capturing
 * arm's zero is the narrower claim of the two: the capture is free because everything around it
 * inlined, and the same lambda stored into a field and read back later would allocate.
 *
 * <p><b>The lease costs 24 bytes, in one shape.</b> Those 24 bytes -- an object header, the
 * captured queue, the captured attempt number -- appear only in the virtual arm at {@code FOUR},
 * which is exactly where {@code onFailure} stopped inlining and C2 could no longer see that the
 * lease does not escape. {@code failsWithHandler} takes the same throw down the {@link
 * ExceptionHandler} branch, which is never handed a lease, and reads zero throughout, so the 24
 * bytes are the lease rather than something else on the failure path.
 *
 * <p><b>Retrying costs 24 bytes always.</b> {@code failsAndRetries} allocates at every type count,
 * including where the lease is provably free. That is the {@code Retry} wrapper the re-admitted
 * item is boxed in, not the lease, and only an item actually resubmitted pays it.
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

  /** Captured by {@link #failsAndGivesUpCapturing}, so that lambda cannot be hoisted. */
  private int mixer;

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

  /**
   * The same strategy written at the call site instead of stored anywhere. A non-capturing lambda
   * links through a {@code ConstantCallSite}, so the {@code invokedynamic} folds to a constant oop
   * of exact type -- the same thing a {@code static final} field gives C2, without the field.
   */
  @Benchmark
  public boolean failsAndGivesUpInline() {
    queue.tryPut(ELEMENT);
    return queue.processOrRetry(THROWS, (item, attempt, failure, q) -> false);
  }

  /**
   * The same lambda made capturing, which is the mistake the inline form invites. It has to be
   * built per call, so nothing folds to a constant -- but the allocation site is still exactly
   * typed, so how much that costs is a separate question from devirtualization.
   */
  @Benchmark
  public boolean failsAndGivesUpCapturing() {
    queue.tryPut(ELEMENT);
    int floor = mixer++;
    return queue.processOrRetry(THROWS, (item, attempt, failure, q) -> attempt < floor);
  }

  /** The control: the same throw down the handler branch, which is never handed a lease. */
  @Benchmark
  public boolean failsWithHandler() {
    queue.tryPut(ELEMENT);
    return queue.processOrHandle(THROWS, HANDLER);
  }
}
