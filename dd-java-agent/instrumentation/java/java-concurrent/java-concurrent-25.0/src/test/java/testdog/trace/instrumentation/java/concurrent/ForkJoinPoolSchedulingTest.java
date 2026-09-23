package testdog.trace.instrumentation.java.concurrent;

import static datadog.context.Context.current;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.isAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.setAsyncPropagationEnabled;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ForkJoinPoolSchedulingTest extends AbstractInstrumentationTest {
  enum Completion {
    COMPLETE,
    QUIETLY_COMPLETE,
    COMPLETE_EXCEPTIONALLY;

    void complete(ForkJoinTask<?> task) {
      switch (this) {
        case COMPLETE:
          task.complete(null);
          break;
        case QUIETLY_COMPLETE:
          task.quietlyComplete();
          break;
        case COMPLETE_EXCEPTIONALLY:
          task.completeExceptionally(new IllegalStateException("external completion"));
          break;
      }
    }
  }

  @ParameterizedTest
  @EnumSource(Completion.class)
  void externallyCompletedTaskReleasesContext(Completion completion) {
    try (ForkJoinPool pool = new ForkJoinPool(1)) {
      AgentSpan parent = startSpan("test", "parent");
      ForkJoinTask<?> task;
      try (ContextScope ignored = current().with(parent).attach()) {
        task = (ForkJoinTask<?>) pool.schedule(() -> {}, 1, DAYS);
      } finally {
        parent.finish();
      }
      completion.complete(task);
      // Check publication while the future is still reachable and before pool cleanup.
      assertTraces(trace(span().root().operationName("parent")));
      assertTrue(task.isDone());
      completion.complete(task);
      task.cancel(false);
      assertTraces(trace(span().root().operationName("parent")));
    }
  }

  @ParameterizedTest
  @EnumSource(Completion.class)
  void externalCompletionPreservesRunningTaskContext(Completion completion) throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    ForkJoinPool pool = new ForkJoinPool(1);
    try {
      CountDownLatch started = new CountDownLatch(1);
      CountDownLatch finished = new CountDownLatch(1);
      AtomicReference<Object> observed = new AtomicReference<>();
      AgentSpan parent = startSpan("test", "parent");
      ForkJoinTask<?> task;
      try (ContextScope ignored = current().with(parent).attach()) {
        task =
            (ForkJoinTask<?>)
                pool.schedule(
                    () -> {
                      started.countDown();
                      try {
                        if (release.await(10, SECONDS)) {
                          observed.set(fromContext(current()));
                          startSpan("test", "child").finish();
                        }
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      } finally {
                        finished.countDown();
                      }
                    },
                    0,
                    MILLISECONDS);
      } finally {
        parent.finish();
      }
      assertTrue(started.await(10, SECONDS));
      completion.complete(task);
      release.countDown();
      assertTrue(finished.await(10, SECONDS));
      assertSame(parent, observed.get());
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfPrevious().operationName("child")));
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void unrelatedApplicationMethodDoesNotCancelContext() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    ForkJoinPool pool = new ForkJoinPool(1);
    try {
      CountDownLatch started = new CountDownLatch(1);
      pool.execute(
          () -> {
            started.countDown();
            try {
              assertTrue(release.await(10, SECONDS));
            } catch (InterruptedException e) {
              throw new AssertionError(e);
            }
          });
      assertTrue(started.await(10, SECONDS));
      AgentSpan parent = startSpan("test", "parent");
      ApplicationTask task = new ApplicationTask();
      try (ContextScope ignored = current().with(parent).attach()) {
        pool.submit(task);
        assertEquals(7, task.trySetCancelled());
      } finally {
        parent.finish();
      }
      release.countDown();
      assertSame(parent, task.get(10, SECONDS));
      assertTraces(trace(span().root().operationName("parent")));
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  public static final class ApplicationTask extends ForkJoinTask<Object> {
    private Object result;

    public int trySetCancelled() {
      return 7;
    }

    @Override
    public Object getRawResult() {
      return result;
    }

    @Override
    protected void setRawResult(Object value) {
      result = value;
    }

    @Override
    protected boolean exec() {
      result = fromContext(current());
      return true;
    }
  }

  @Test
  void scheduledRunnableRetainsParentAfterScopeCloses() throws Exception {
    try (ForkJoinPool pool = new ForkJoinPool(1)) {
      CountDownLatch release = new CountDownLatch(1);
      AgentSpan parent = startSpan("test", "parent");
      Object expectedParent = parent;
      ScheduledFuture<?> future;
      try (ContextScope ignored = current().with(parent).attach()) {
        future =
            pool.schedule(
                () -> {
                  try {
                    assertTrue(release.await(10, SECONDS));
                  } catch (InterruptedException e) {
                    throw new AssertionError(e);
                  }
                  assertSame(expectedParent, fromContext(current()));
                  startSpan("test", "child").finish();
                },
                1,
                MILLISECONDS);
      } finally {
        parent.finish();
        release.countDown();
      }
      future.get(10, SECONDS);
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfPrevious().operationName("child")));
     pool.submit(() -> assertNull(AgentSpan.current()))
          .get(10, SECONDS);
    }
  }

  @Test
  void scheduledCallablePropagatesContextAndResult() throws Exception {
    try (ForkJoinPool pool = new ForkJoinPool(1)) {
      AgentSpan parent = startSpan("test", "parent");
      Object expectedParent = parent;
      try (ContextScope ignored = current().with(parent).attach()) {
        assertEquals(
            42,
            pool.schedule(
                    () -> {
                      assertSame(expectedParent, fromContext(current()));
                      return 42;
                    },
                    0,
                    MILLISECONDS)
                .get(10, SECONDS));
      } finally {
        parent.finish();
      }
      assertTraces(trace(span().root().operationName("parent")));
    }
  }

  @Test
  void failingTaskReleasesContext() throws Exception {
    try (ForkJoinPool pool = new ForkJoinPool(1)) {
      AgentSpan parent = startSpan("test", "parent");
      Object expectedParent = parent;
      IllegalStateException failure = new IllegalStateException("task failure");
      try (ContextScope ignored = current().with(parent).attach()) {
        ScheduledFuture<?> future =
            pool.schedule(
                () -> {
                  assertSame(expectedParent, fromContext(current()));
                  throw failure;
                },
                1,
                MILLISECONDS);
        Throwable cause =
            assertThrows(ExecutionException.class, () -> future.get(10, SECONDS)).getCause();
        // ForkJoinTask may reconstruct the exception when reporting it on another thread.
        assertTrue(cause == failure || cause.getCause() == failure);
      } finally {
        parent.finish();
      }
      assertTraces(trace(span().root().operationName("parent")));
      pool.submit(
              () -> {
                assertNull(fromContext(current()));
              })
          .get(10, SECONDS);
    }
  }

  @Test
  void cancelledTaskReleasesContext() {
    try (ForkJoinPool pool = new ForkJoinPool(1)) {
      AgentSpan parent = startSpan("test", "parent");
      try (ContextScope ignored = current().with(parent).attach()) {
        assertTrue(pool.schedule(() -> {}, 1, DAYS).cancel(false));
      } finally {
        parent.finish();
      }
      assertTraces(trace(span().root().operationName("parent")));
    }
  }

  @Test
  void rejectedTaskReleasesContext() {
    try (ForkJoinPool pool = new ForkJoinPool(1)) {
      pool.shutdown();
      AgentSpan parent = startSpan("test", "parent");
      try (ContextScope ignored = current().with(parent).attach()) {
        assertThrows(RejectedExecutionException.class, () -> pool.schedule(() -> {}, 1, DAYS));
      } finally {
        parent.finish();
      }
      assertTraces(trace(span().root().operationName("parent")));
    }
  }

  @Test
  void shutdownCancelsDelayedTaskAndReleasesContext() throws Exception {
    try (ForkJoinPool pool = new ForkJoinPool(1)) {
      AgentSpan parent = startSpan("test", "parent");
      ScheduledFuture<?> future;
      try (ContextScope ignored = current().with(parent).attach()) {
        future = pool.schedule(() -> {}, 1, DAYS);
      } finally {
        parent.finish();
      }
      pool.cancelDelayedTasksOnShutdown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(10, SECONDS));
      assertTrue(future.isCancelled());
      assertTraces(trace(span().root().operationName("parent")));
    }
  }

  @Test
  void periodicTasksDoNotRetainParent() throws Exception {
    try (ForkJoinPool pool = new ForkJoinPool(1)) {
      AgentSpan parent = startSpan("test", "parent");
      CountDownLatch ran = new CountDownLatch(2);
      Runnable task =
          () -> {
            assertNull(fromContext(current()));
            ran.countDown();
          };
      ScheduledFuture<?> fixedRate;
      ScheduledFuture<?> fixedDelay;
      try (ContextScope ignored = current().with(parent).attach()) {
        fixedRate = pool.scheduleAtFixedRate(task, 0, 1, DAYS);
        fixedDelay = pool.scheduleWithFixedDelay(task, 0, 1, DAYS);
      } finally {
        parent.finish();
      }
      try {
        assertTrue(ran.await(10, SECONDS));
        assertTraces(trace(span().root().operationName("parent")));
      } finally {
        fixedRate.cancel(false);
        fixedDelay.cancel(false);
      }
    }
  }

  @Test
  void disabledPropagationDoesNotCaptureParent() throws Exception {
    try (ForkJoinPool pool = new ForkJoinPool(1)) {
      AgentSpan parent = startSpan("test", "parent");
      try (ContextScope ignored = current().with(parent).attach()) {
        boolean enabled = isAsyncPropagationEnabled();
        setAsyncPropagationEnabled(false);
        try {
          pool.schedule(
                  () -> {
                    assertNull(fromContext(current()));
                  },
                  0,
                  MILLISECONDS)
              .get(10, SECONDS);
        } finally {
          setAsyncPropagationEnabled(enabled);
        }
      } finally {
        parent.finish();
      }
      assertTraces(trace(span().root().operationName("parent")));
    }
  }

  @Test
  void internalTimeoutDoesNotRetainParentAfterTaskCompletes() throws Exception {
    try (ForkJoinPool pool = new ForkJoinPool(1)) {
      AgentSpan parent = startSpan("test", "parent");
      Object expectedParent = parent;
      try (ContextScope ignored = current().with(parent).attach()) {
        assertEquals(
            42,
            pool.submitWithTimeout(
                    () -> {
                      assertSame(expectedParent, fromContext(current()));
                      return 42;
                    },
                    1,
                    DAYS,
                    null)
                .get(10, SECONDS));
      } finally {
        parent.finish();
      }
      assertTraces(trace(span().root().operationName("parent")));
    }
  }
}
