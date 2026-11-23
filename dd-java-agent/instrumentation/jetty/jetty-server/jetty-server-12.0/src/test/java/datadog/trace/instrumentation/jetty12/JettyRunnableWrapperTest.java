package datadog.trace.instrumentation.jetty12;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jetty.util.thread.Invocable;
import org.eclipse.jetty.util.thread.Invocable.InvocationType;
import org.eclipse.jetty.util.thread.strategy.AdaptiveExecutionStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class JettyRunnableWrapperTest {
  private static final Context REQUEST = Context.root().with(ContextKey.named("request"), "jetty");

  @ParameterizedTest
  @EnumSource(InvocationType.class)
  void preservesInvocationTypeAndContext(InvocationType type) {
    Context caller = Context.current();
    AtomicBoolean ran = new AtomicBoolean();
    Runnable task =
        wrap(
            Invocable.from(
                type,
                () -> {
                  assertSame(REQUEST, Context.current());
                  ran.set(true);
                }));

    assertEquals(type, Invocable.getInvocationType(task));
    assertSame(caller, Context.current());
    task.run();
    assertTrue(ran.get());
    assertSame(caller, Context.current());
  }

  @Test
  void plainRunnableRemainsBlocking() {
    Runnable task = wrap(() -> {});
    assertEquals(InvocationType.BLOCKING, Invocable.getInvocationType(task));
    task.run();
  }

  @Test
  void restoresContextWhenTaskThrows() {
    Context caller = Context.current();
    RuntimeException failure = new RuntimeException("task failed");
    Runnable task =
        wrap(
            Invocable.from(
                InvocationType.NON_BLOCKING,
                () -> {
                  assertSame(REQUEST, Context.current());
                  throw failure;
                }));
    assertSame(failure, assertThrows(RuntimeException.class, task::run));
    assertSame(caller, Context.current());
  }

  @Test
  void nonBlockingTaskRunsWithoutExecutorHandoff() throws Exception {
    Queue<Runnable> produced = new ArrayDeque<>();
    Queue<Runnable> dispatched = new ArrayDeque<>();
    AtomicBoolean ran = new AtomicBoolean();
    Thread producer = Thread.currentThread();
    produced.add(
        wrap(
            Invocable.from(
                InvocationType.NON_BLOCKING,
                () -> {
                  assertSame(producer, Thread.currentThread());
                  assertSame(REQUEST, Context.current());
                  ran.set(true);
                })));
    AdaptiveExecutionStrategy strategy =
        new AdaptiveExecutionStrategy(produced::poll, dispatched::add);
    Context caller = Context.current();
    strategy.start();
    try {
      strategy.produce();
      assertTrue(ran.get());
      assertTrue(dispatched.isEmpty(), "nonblocking tasks should run on the producer");
      assertSame(caller, Context.current());
    } finally {
      strategy.stop();
      while (!dispatched.isEmpty()) {
        dispatched.remove().run();
      }
    }
  }

  private static Runnable wrap(Runnable task) {
    try (ContextScope ignored = REQUEST.attach()) {
      return JettyRunnableWrapper.wrapIfNeeded(task);
    }
  }
}
