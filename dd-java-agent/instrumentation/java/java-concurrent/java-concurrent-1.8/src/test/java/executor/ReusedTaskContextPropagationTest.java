package executor;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceAssertions.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.TraceMatcher;
import datadog.trace.api.Trace;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Context propagation when the <em>same</em> task instance is submitted more than once.
 *
 * <p>The non-wrapping {@link java.util.concurrent.ThreadPoolExecutor} path stores the captured
 * context in a {@code State} attached to the task instance, and a {@code State} holds a single
 * continuation. So when a task instance is submitted again while an earlier submission is still
 * queued, the two submissions compete for one slot. Every submission must still run under the
 * parent it was submitted from.
 */
public class ReusedTaskContextPropagationTest extends AbstractInstrumentationTest {

  /** Records which span is active when the task body runs, in execution order. */
  static final class ParentRecorder {
    final List<String> parents = new CopyOnWriteArrayList<>();
    final CountDownLatch ran;

    ParentRecorder(int expectedRuns) {
      this.ran = new CountDownLatch(expectedRuns);
    }

    void record() {
      AgentSpan span = activeSpan();
      parents.add(span == null ? "<none>" : String.valueOf(span.getOperationName()));
      child();
      ran.countDown();
    }

    @Trace(operationName = "child")
    void child() {}
  }

  /** A plain named class, the shape most application tasks take. */
  static final class NamedTask implements Runnable {
    private final ParentRecorder recorder;

    NamedTask(ParentRecorder recorder) {
      this.recorder = recorder;
    }

    @Override
    public void run() {
      recorder.record();
    }
  }

  @Test
  void namedClassInstanceQueuedTwiceUnderDifferentParents() throws Exception {
    ParentRecorder recorder = new ParentRecorder(2);
    assertBothParentsPropagate(new NamedTask(recorder), recorder);
  }

  @Test
  void anonymousInnerClassInstanceQueuedTwiceUnderDifferentParents() throws Exception {
    ParentRecorder recorder = new ParentRecorder(2);
    Runnable task =
        new Runnable() {
          @Override
          public void run() {
            recorder.record();
          }
        };
    assertBothParentsPropagate(task, recorder);
  }

  @Test
  void lambdaInstanceQueuedTwiceUnderDifferentParents() throws Exception {
    ParentRecorder recorder = new ParentRecorder(2);
    assertBothParentsPropagate(recorder::record, recorder);
  }

  /**
   * Sequential reuse: the first submission completes before the second is made, so the single slot
   * is free again and no fallback is needed.
   */
  @Test
  void sameInstanceReusedSequentiallyUnderDifferentParents() throws Exception {
    ParentRecorder recorder = new ParentRecorder(2);
    Runnable task = new NamedTask(recorder);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      submitUnderParentA(pool, task);
      submitUnderParentB(pool, task);
      assertTrue(recorder.ran.await(20, TimeUnit.SECONDS), "task did not run twice");
      assertEquals(Arrays.asList("parentA", "parentB"), recorder.parents);
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Queues both submissions of {@code task} behind a blocked worker, then asserts each execution
   * ran under the parent it was submitted from.
   */
  private void assertBothParentsPropagate(Runnable task, ParentRecorder recorder) throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      CountDownLatch release = new CountDownLatch(1);
      CountDownLatch workerBusy = new CountDownLatch(1);
      pool.execute(
          new Runnable() {
            @Override
            public void run() {
              workerBusy.countDown();
              try {
                release.await(20, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            }
          });
      assertTrue(
          workerBusy.await(20, TimeUnit.SECONDS), "worker never picked up the blocking task");

      // Both submissions are now queued, so both compete for the task's single State slot.
      submitUnderParentA(pool, task);
      submitUnderParentB(pool, task);

      release.countDown();
      assertTrue(recorder.ran.await(20, TimeUnit.SECONDS), "task did not run twice");

      assertEquals(Arrays.asList("parentA", "parentB"), recorder.parents);
      // each submission keeps its own trace, with the task's span parented correctly
      assertTraces(
          SORT_BY_START_TIME,
          trace(
              TraceMatcher.SORT_BY_START_TIME,
              span().root().operationName("parentA"),
              span().childOfPrevious().operationName("child")),
          trace(
              TraceMatcher.SORT_BY_START_TIME,
              span().root().operationName("parentB"),
              span().childOfPrevious().operationName("child")));
    } finally {
      pool.shutdownNow();
    }
  }

  @Trace(operationName = "parentA")
  void submitUnderParentA(ExecutorService pool, Runnable task) {
    pool.execute(task);
  }

  @Trace(operationName = "parentB")
  void submitUnderParentB(ExecutorService pool, Runnable task) {
    pool.execute(task);
  }
}
