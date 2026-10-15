package executor;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.Trace;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.grpc.netty.shaded.io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.grpc.netty.shaded.io.netty.util.concurrent.EventExecutor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The fix in {@code RunnableFutureInstrumentation} matches Netty's {@code ScheduledFutureTask} by
 * the {@code .netty.util.concurrent.ScheduledFutureTask} class-name suffix so it also covers shaded
 * Netty copies. This verifies context propagation through a delayed task on grpc-netty-shaded's
 * (relocated) {@code DefaultEventExecutorGroup}.
 */
class GrpcShadedNettyScheduledFutureTaskContextPropagationTest extends AbstractInstrumentationTest {
  @Test
  void testDelayedTaskPropagatesContextWithShadedNetty() throws Exception {
    try (CloseableDefaultEventExecutorGroup group = new CloseableDefaultEventExecutorGroup()) {
      EventExecutor executor = group.next();
      TraceableTask task = new TraceableTask();
      AgentSpan parent = startSpan("test", "parent");

      try (AgentScope ignored = activateSpan(parent)) {
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

    @Trace(operationName = "asyncChild")
    private void asyncChild() {}
  }
}
