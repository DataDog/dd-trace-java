package executor;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.Trace;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.grpc.netty.shaded.io.netty.util.concurrent.AbstractScheduledEventExecutor;
import io.grpc.netty.shaded.io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.grpc.netty.shaded.io.netty.util.concurrent.EventExecutor;
import io.grpc.netty.shaded.io.netty.util.concurrent.ScheduledFuture;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The Netty instrumentation matches {@code PromiseTask} by the {@code
 * .netty.util.concurrent.PromiseTask} class-name suffix so it also covers shaded Netty copies. This
 * verifies context propagation through a delayed task on grpc-netty-shaded's relocated {@code
 * DefaultEventExecutorGroup}.
 */
class GrpcShadedNettyScheduledFutureTaskContextPropagationTest extends AbstractInstrumentationTest {
  /**
   * Verifies that a task scheduled with a delay on a shaded {@code DefaultEventExecutorGroup} still
   * sees the scheduling span as active when it runs, and that a traced method invoked from the task
   * becomes a child of that span.
   */
  @Test
  void testDelayedTaskPropagatesContextWithShadedNetty() throws Exception {
    try (CloseableDefaultEventExecutorGroup group = new CloseableDefaultEventExecutorGroup()) {
      EventExecutor executor = group.next();
      TraceableTask task = new TraceableTask();
      AgentSpan parent = startSpan("test", "parent");

      try (ContextScope ignored = activateSpan(parent)) {
        executor.schedule(task, 50, MILLISECONDS);
      } finally {
        parent.finish();
      }

      assertTrue(task.finished.await(5, SECONDS));
      assertTrue(task.sawActiveSpan.get());
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfPrevious().operationName("asyncChild")));
    }
  }

  /**
   * Verifies that a listener added to a delayed task's future sees the scheduling span as active
   * when the task completes successfully, and that the scope is closed afterwards.
   */
  @Test
  void testSuccessListenerKeepsSchedulingContext() throws Exception {
    assertCompletionListenerContext(false);
  }

  /**
   * Same as {@link #testSuccessListenerKeepsSchedulingContext()} but for a task that fails,
   * verifying the scheduling context still propagates to the listener on failure.
   */
  @Test
  void testFailureListenerKeepsSchedulingContext() throws Exception {
    assertCompletionListenerContext(true);
  }

  /**
   * Verifies that running a nested scheduled task inline (via reflection, bypassing the executor)
   * does not leak its context: the outer task's active span is restored once the nested task
   * finishes running, both for a newly-scheduled continuation and for one that reuses the outer
   * scope.
   */
  @Test
  void testNestedScheduledRunRestoresOuterContext() throws Exception {
    try (CloseableDefaultEventExecutorGroup group = new CloseableDefaultEventExecutorGroup()) {
      EventExecutor executor = group.next();
      executor.submit(() -> {}).sync();
      AgentSpan parent = startSpan("test", "parent");
      ScheduledFuture<?> outer;
      try (ContextScope ignored = activateSpan(parent)) {
        outer =
            executor.schedule(
                () -> {
                  AgentSpan outerSpan = activeSpan();
                  AgentSpan child = startSpan("test", "nested");
                  ScheduledFuture<?> inner;
                  try (ContextScope innerScope = activateSpan(child)) {
                    inner = executor.schedule(() -> {}, 0, MILLISECONDS);
                  } finally {
                    child.finish();
                  }
                  // Remove the due task before running it inline; Netty cannot run it twice.
                  try {
                    Method poll =
                        AbstractScheduledEventExecutor.class.getDeclaredMethod("pollScheduledTask");
                    poll.setAccessible(true);
                    assertSame(inner, poll.invoke(executor));
                  } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                  }
                  ((Runnable) inner).run();
                  assertTrue(inner.isSuccess());
                  assertSame(outerSpan, activeSpan());
                  // The legacy context manager reuses the outer scope for this continuation.
                  ScheduledFuture<?> sameContext = executor.schedule(() -> {}, 0, MILLISECONDS);
                  try {
                    Method poll =
                        AbstractScheduledEventExecutor.class.getDeclaredMethod("pollScheduledTask");
                    poll.setAccessible(true);
                    assertSame(sameContext, poll.invoke(executor));
                  } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                  }
                  ((Runnable) sameContext).run();
                  assertTrue(sameContext.isSuccess());
                  assertSame(outerSpan, activeSpan());
                },
                50,
                MILLISECONDS);
      } finally {
        parent.finish();
      }
      outer.get(5, SECONDS);
      assertNull(executor.submit(() -> (Object) activeSpan()).get(5, SECONDS));
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfPrevious().operationName("nested")));
    }
  }

  private void assertCompletionListenerContext(boolean fail) throws Exception {
    try (CloseableDefaultEventExecutorGroup group = new CloseableDefaultEventExecutorGroup()) {
      EventExecutor executor = group.next();
      // Start the worker before capturing the scheduling span.
      executor.submit(() -> {}).sync();
      CountDownLatch proceed = new CountDownLatch(1);
      CountDownLatch completed = new CountDownLatch(1);
      AtomicReference<AgentSpan> listenerSpan = new AtomicReference<>();
      AgentSpan parent = startSpan("test", "parent");
      ScheduledFuture<?> future;
      try (ContextScope ignored = activateSpan(parent)) {
        future =
            executor.schedule(
                () -> {
                  try {
                    if (!proceed.await(5, SECONDS)) {
                      throw new AssertionError("Listener was not registered");
                    }
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                  }
                  if (fail) {
                    throw new IllegalStateException("expected task failure");
                  }
                },
                50,
                MILLISECONDS);
      } finally {
        parent.finish();
      }
      // Register without a span so listener instrumentation cannot mask early scope closure.
      future.addListener(
          done -> {
            listenerSpan.set(activeSpan());
            completed.countDown();
          });
      proceed.countDown();
      assertTrue(completed.await(5, SECONDS));
      assertSame(parent, listenerSpan.get());
      assertTrue(fail != future.isSuccess());
      assertNull(executor.submit(() -> (Object) activeSpan()).get(5, SECONDS));
      assertTraces(trace(span().root().operationName("parent")));
    }
  }

  private static final class CloseableDefaultEventExecutorGroup extends DefaultEventExecutorGroup
      implements AutoCloseable {
    private CloseableDefaultEventExecutorGroup() {
      super(1);
    }

    @Override
    public void close() {
      try {
        shutdownGracefully(0, 1, SECONDS).sync();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final class TraceableTask implements Runnable {
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicBoolean sawActiveSpan = new AtomicBoolean();

    @Override
    public void run() {
      sawActiveSpan.set(activeSpan() != null);
      try {
        asyncChild();
      } finally {
        finished.countDown();
      }
    }

    // No-op body: only the @Trace annotation matters, it starts+finishes a child span so the
    // test can assert it is parented to the span active when the task ran.
    @Trace(operationName = "asyncChild")
    private void asyncChild() {}
  }
}
