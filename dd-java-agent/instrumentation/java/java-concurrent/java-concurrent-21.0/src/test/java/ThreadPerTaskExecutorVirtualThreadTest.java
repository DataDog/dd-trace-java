import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.Trace;
import datadog.trace.core.DDSpan;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

/**
 * Reproductions for the executor swap profiling-backend PR#8520 made for OkHttp's Dispatcher
 * (cached thread pool &rarr; {@code Executors.newThreadPerTaskExecutor(Thread.ofVirtual()...)}).
 *
 * <p>Each test asserts that an {@code @Trace}-annotated child method invoked from inside a task
 * submitted to the virtual-thread-per-task executor attaches to the parent span that was active at
 * submission time. If propagation breaks, the child either lands in a different trace or floats
 * free as a root span and the {@code assertEquals(parentSpanId, childSpan.getParentId())} check
 * fails.
 *
 * <p>This mirrors what would happen with OkHttp: the {@code okhttp} client span is created by
 * {@code TracingInterceptor.intercept()} on the executor thread using {@code activeSpan()}, so "is
 * the parent scope active on the worker?" is the entire question.
 */
class ThreadPerTaskExecutorVirtualThreadTest extends AbstractInstrumentationTest {

  @Trace(operationName = "parent")
  void underParentTrace(int expectedChildren, Runnable body) {
    body.run();
    blockUntilChildSpansFinished(expectedChildren);
  }

  @Trace(operationName = "child")
  static void child() {}

  @Test
  void virtualThreadFactory_propagatesActiveScope() throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    try {
      underParentTrace(1, () -> executor.execute(ThreadPerTaskExecutorVirtualThreadTest::child));
    } finally {
      executor.shutdown();
    }

    writer.waitForTraces(1);
    List<DDSpan> trace = writer.get(0);
    assertEquals(2, trace.size(), "expected parent + child span");
    DDSpan parentSpan = findByOp(trace, "parent");
    DDSpan childSpan = findByOp(trace, "child");
    assertNotNull(parentSpan, "parent span should exist");
    assertNotNull(childSpan, "child span should exist");
    assertEquals(
        parentSpan.getSpanId(),
        childSpan.getParentId(),
        "child span should be parented under the active span at executor.execute()");
  }

  @Test
  void namedVirtualThreadFactory_propagatesActiveScope() throws Exception {
    // Exact builder shape from profiling-backend PR#8520:
    //   Thread.ofVirtual().name("okhttp-" + track + "-", 0).factory()
    ExecutorService executor =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("okhttp-test-", 0).factory());
    try {
      underParentTrace(1, () -> executor.execute(ThreadPerTaskExecutorVirtualThreadTest::child));
    } finally {
      executor.shutdown();
    }

    writer.waitForTraces(1);
    List<DDSpan> trace = writer.get(0);
    DDSpan parentSpan = findByOp(trace, "parent");
    DDSpan childSpan = findByOp(trace, "child");
    assertNotNull(parentSpan, "parent span should exist");
    assertNotNull(childSpan, "child span should exist");
    assertEquals(
        parentSpan.getSpanId(),
        childSpan.getParentId(),
        "the .name(prefix, start) builder should not break propagation");
  }

  /**
   * Mirrors OkHttp's dispatcher recursion: a task running on the executor submits more work back to
   * the same executor. In OkHttp this is {@code Dispatcher.finished() &rarr; promoteAndExecute()
   * &rarr; executorService.execute(nextAsyncCall)}, called from inside an {@code AsyncCall.run()}
   * on a dispatcher thread.
   *
   * <p>Both child spans should land in the parent's trace. If the worker thread loses the parent
   * scope between the outer activation and the inner submission, the second child either becomes a
   * new root span or gets attached to the wrong sibling.
   */
  @Test
  void recursiveSubmissionFromWorkerThread_keepsTraceConnected() throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    try {
      underParentTrace(
          2,
          () ->
              executor.execute(
                  () -> {
                    child();
                    executor.execute(ThreadPerTaskExecutorVirtualThreadTest::child);
                  }));
    } finally {
      executor.shutdown();
    }

    writer.waitForTraces(1);
    List<DDSpan> trace = writer.get(0);
    DDSpan parentSpan = findByOp(trace, "parent");
    assertNotNull(parentSpan, "parent span should exist");
    long parentTraceId = parentSpan.getTraceId().toLong();
    long parentSpanId = parentSpan.getSpanId();

    long childCount =
        trace.stream().filter(s -> "child".contentEquals(s.getOperationName())).count();
    assertEquals(2, childCount, "both child spans should land in the same trace as the parent");

    trace.stream()
        .filter(s -> "child".contentEquals(s.getOperationName()))
        .forEach(
            s -> {
              assertEquals(
                  parentTraceId,
                  s.getTraceId().toLong(),
                  "child span must share the parent's trace");
              assertEquals(
                  parentSpanId,
                  s.getParentId(),
                  "child span must attach to the parent, not float free");
            });
  }

  /**
   * Forces the virtual thread to unmount and remount mid-task by sleeping between two child spans.
   * This is the case OkHttp actually hits in practice &mdash; every blocking socket read unmounts
   * the carrier, and the scope stack has to be reinstated by {@code VirtualThreadInstrumentation}
   * on each remount. The fix log for this area is long (#10931, #11009, #11111), so it is worth
   * pinning down with a regression test.
   */
  @Test
  void virtualThreadFactory_propagatesAcrossUnmount() throws Exception {
    ExecutorService executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory());
    try {
      underParentTrace(
          2,
          () ->
              executor.execute(
                  () -> {
                    child();
                    try {
                      // Sleep is a JDK 21+ carrier-unmounting parking point for virtual threads.
                      Thread.sleep(50);
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                    }
                    child();
                  }));
    } finally {
      executor.shutdown();
    }

    writer.waitForTraces(1);
    List<DDSpan> trace = writer.get(0);
    DDSpan parentSpan = findByOp(trace, "parent");
    assertNotNull(parentSpan, "parent span should exist");
    long parentSpanId = parentSpan.getSpanId();

    long childCount =
        trace.stream().filter(s -> "child".contentEquals(s.getOperationName())).count();
    assertEquals(2, childCount, "both child spans should be captured");

    trace.stream()
        .filter(s -> "child".contentEquals(s.getOperationName()))
        .forEach(
            s ->
                assertEquals(
                    parentSpanId,
                    s.getParentId(),
                    "child span before and after virtual-thread unmount must both attach to parent"));
  }

  private static DDSpan findByOp(List<DDSpan> spans, String op) {
    return spans.stream()
        .filter(s -> op.contentEquals(s.getOperationName()))
        .findFirst()
        .orElse(null);
  }
}
