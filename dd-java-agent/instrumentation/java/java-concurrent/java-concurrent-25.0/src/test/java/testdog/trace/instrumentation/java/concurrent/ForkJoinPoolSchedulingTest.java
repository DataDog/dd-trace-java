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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;

class ForkJoinPoolSchedulingTest extends AbstractInstrumentationTest {
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
      pool.submit(
              () -> {
                assertNull(fromContext(current()));
              })
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
