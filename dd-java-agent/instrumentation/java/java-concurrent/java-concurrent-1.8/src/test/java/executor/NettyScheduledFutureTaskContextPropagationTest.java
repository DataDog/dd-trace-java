package executor;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.Trace;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.util.Version;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutor;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class NettyScheduledFutureTaskContextPropagationTest extends AbstractInstrumentationTest {
  @Test
  void testNettyVersionCompatible() {
    assertFalse(isCompatibleVersion("4.0.0.Final"));
    assertFalse(isCompatibleVersion("4.0.44.Final"));
    assertFalse(isCompatibleVersion("4.0.99.Final"));
    assertFalse(isCompatibleVersion("4.1.9.Final"));
    assertFalse(isCompatibleVersion("4.1.43.Final"));
    assertTrue(isCompatibleVersion("4.1.44.Final"));
    assertTrue(isCompatibleVersion("4.2.13.Final"));
    assertTrue(isCompatibleVersion("5.0.0.Alpha2"));
    assertTrue(isCompatibleVersion("5.0.0.Final"));
  }

  @Test
  void testDelayedScheduledFutureTaskActivatesCapturedContinuationWhenDelayExpires()
      throws Exception {
    assumeTrue(hasCompatibleVersion());

    try (CloseableDefaultEventExecutorGroup group = new CloseableDefaultEventExecutorGroup()) {
      EventExecutor executor = group.next();
      BlockingTraceableTask task = new BlockingTraceableTask();
      AgentSpan parent = startSpan("test", "parent");

      // Netty 4.1.44+ calls ScheduledFutureTask.run() once while enqueueing a delayed task and
      // again when the delay expires. The continuation captured here must survive the enqueue run.
      try (AgentScope ignored = activateSpan(parent)) {
        executor.schedule(task, 50, MILLISECONDS);
      } finally {
        parent.finish();
      }

      // When the delayed task actually runs, instrumentation should activate the captured
      // continuation so traced work in the task remains a child of the scheduling span.
      assertTrue(task.started.await(5, SECONDS));
      try {
        assertTrue(task.sawActiveSpan.get());
      } finally {
        task.proceed.countDown();
      }
      assertTrue(task.finished.await(5, SECONDS));

      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfPrevious().operationName("asyncChild")));
    }
  }

  @Test
  void testDelayedTaskPropagatesContextOnAllNettyVersions() throws Exception {
    // Cross-version invariant: context propagation through a delayed task must work on every
    // supported Netty version — pre-4.1.44 (single run() at the deadline) and 4.1.44+ (a delay > 0
    // self-enqueue run followed by the real fire). This guards against the fix regressing the
    // versions that were never broken.
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

  @Test
  void testImmediateScheduledTaskKeepsContext() throws Exception {
    // A ScheduledFutureTask scheduled with a non-positive delay only ever runs its body when
    // delayNanos <= 0 (Netty self-enqueues only while delay > 0). The fix's "delay > 0" skip must
    // therefore NOT apply to immediate tasks, so the captured continuation must still activate.
    try (CloseableDefaultEventExecutorGroup group = new CloseableDefaultEventExecutorGroup()) {
      EventExecutor executor = group.next();
      TraceableTask task = new TraceableTask();
      AgentSpan parent = startSpan("test", "parent");

      try (AgentScope ignored = activateSpan(parent)) {
        executor.schedule(task, 0, MILLISECONDS);
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

  private static boolean hasCompatibleVersion() {
    for (Map.Entry<String, Version> entry : Version.identify().entrySet()) {
      if (entry.getKey().startsWith("netty-")) {
        return isCompatibleVersion(entry.getValue().artifactVersion());
      }
    }
    return false;
  }

  private static boolean isCompatibleVersion(String version) {
    String[] parts = version.split("\\.");
    if (parts.length < 3) {
      return false;
    }
    int major = Integer.parseInt(parts[0]);
    int minor = Integer.parseInt(parts[1]);
    int patch = Integer.parseInt(parts[2]);
    // Netty uses a self-enqueue path for delayed tasks since 4.1.44.
    return major > 4 || (major == 4 && (minor > 1 || (minor == 1 && patch >= 44)));
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

  private static final class BlockingTraceableTask implements Runnable {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch proceed = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(1);
    private final AtomicBoolean sawActiveSpan = new AtomicBoolean();

    @Override
    public void run() {
      sawActiveSpan.set(activeSpan() != null);
      started.countDown();
      try {
        proceed.await(5, SECONDS);
        asyncChild();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        finished.countDown();
      }
    }

    @Trace(operationName = "asyncChild")
    private void asyncChild() {}
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
