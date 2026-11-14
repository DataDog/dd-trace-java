package executor;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.TPEHelper;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.apache.tomcat.util.threads.TaskQueue;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.tabletest.junit.TableTest;

class TomcatThreadPoolExecutorTest extends AbstractInstrumentationTest {
  @TableTest({
    "Operation       | subclass",
    "execute         | false   ",
    "lambda          | false   ",
    "submit runnable | false   ",
    "submit callable | false   ",
    "invokeAll       | false   ",
    "invokeAny       | false   ",
    "timed execute   | false   ",
    "execute         | true    ",
    "lambda          | true    ",
    "submit runnable | true    ",
    "submit callable | true    ",
    "invokeAll       | true    ",
    "invokeAny       | true    ",
    "timed execute   | true    "
  })
  void propagatesAndClearsContext(String operation, boolean subclass) throws Exception {
    try (Pool pool = new Pool(new TaskQueue(), subclass)) {
      if (operation.equals("timed execute")) {
        assumeTrue(hasTimedExecute());
      }
      CheckingTask task = new CheckingTask();
      AgentSpan parent = startSpan("test", "parent");
      try (ContextScope ignored = activateSpan(parent)) {
        dispatch(pool.executor, task, operation);
      } finally {
        parent.finish();
      }
      assertTrue(task.done.await(5, SECONDS));
      assertSame(parent, task.observed);
      assertNull(pool.executor.submit(() -> (Object) activeSpan()).get(5, SECONDS));
      if (subclass
          && !TPEHelper.useWrapping(task)
          && (operation.equals("execute") || operation.equals("timed execute"))) {
        assertSame(parent, ((HookedExecutor) pool.executor).before);
        assertSame(parent, ((HookedExecutor) pool.executor).after);
      }
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfPrevious().operationName("child")));
    }
  }

  @TableTest({
    "Operation       | wrapped",
    "execute         | false  ",
    "execute         | true   ",
    "submit runnable | false  ",
    "timed execute   | false  "
  })
  void finalRejectionReleasesContext(String operation, boolean wrapped) throws Exception {
    assumeFork();
    if (operation.equals("timed execute")) {
      assumeTrue(hasTimedExecute());
    }
    try (Pool pool = new Pool(new TaskQueue(1), false)) {
      pool.block();
      pool.executor.execute(new CheckingTask());
      CheckingTask task = new CheckingTask();
      AgentSpan parent = startSpan("test", "parent");
      try (ContextScope ignored = activateSpan(parent)) {
        assertThrows(
            RejectedExecutionException.class,
            () -> dispatch(pool.executor, wrapped ? () -> task.run() : task, operation));
      } finally {
        parent.finish();
      }
      assertEquals(1, task.done.getCount());
      // Keep the worker blocked: a trace must complete without the rejected task ever running.
      assertTraces(trace(span().root().operationName("parent")));
    }
  }

  @TableTest({
    "Policy           | wrapped",
    "DiscardPolicy    | false  ",
    "DiscardPolicy    | true   ",
    "CallerRunsPolicy | false  ",
    "CallerRunsPolicy | true   "
  })
  void rejectionPoliciesReleaseContext(String policy, boolean wrapped) throws Exception {
    assumeFork();
    try (Pool pool = new Pool(new TaskQueue(1), false)) {
      Class<?> handlerType =
          ThreadPoolExecutor.class.getMethod("getRejectedExecutionHandler").getReturnType();
      Object handler =
          Class.forName(ThreadPoolExecutor.class.getName() + "$" + policy)
              .getDeclaredConstructor()
              .newInstance();
      ThreadPoolExecutor.class
          .getMethod("setRejectedExecutionHandler", handlerType)
          .invoke(pool.executor, handler);
      pool.block();
      pool.executor.execute(new CheckingTask());
      CheckingTask task = new CheckingTask();
      AgentSpan parent = startSpan("test", "parent");
      try (ContextScope ignored = activateSpan(parent)) {
        pool.executor.execute(wrapped ? () -> task.run() : task);
      } finally {
        parent.finish();
      }
      if (policy.equals("CallerRunsPolicy")) {
        assertSame(parent, task.observed);
        assertTraces(
            trace(
                SORT_BY_START_TIME,
                span().root().operationName("parent"),
                span().childOfPrevious().operationName("child")));
      } else {
        assertEquals(1, task.done.getCount());
        assertTraces(trace(span().root().operationName("parent")));
      }
    }
  }

  @TableTest({
    "Scenario | wrapped",
    "runnable | false  ",
    "lambda   | true   "
  })
  void successfulRemovalReleasesContext(boolean wrapped) throws Exception {
    assumeFork();
    try (Pool pool = new Pool(new TaskQueue(1), false)) {
      pool.block();
      CheckingTask task = new CheckingTask();
      AgentSpan parent = startSpan("test", "parent");
      try (ContextScope ignored = activateSpan(parent)) {
        pool.executor.execute(wrapped ? () -> task.run() : task);
      } finally {
        parent.finish();
      }
      Runnable queued = pool.executor.getQueue().peek();
      assertTrue(pool.executor.remove(queued));
      assertFalse(pool.executor.remove(queued));
      assertEquals(1, task.done.getCount());
      assertTraces(trace(span().root().operationName("parent")));
    }
  }

  @TableTest({
    "Scenario | wrapped",
    "runnable | false  ",
    "lambda   | true   "
  })
  void failedRemovalPreservesContext(boolean wrapped) throws Exception {
    assumeFork();
    TaskQueue queue =
        new TaskQueue() {
          @Override
          public boolean remove(Object task) {
            return false;
          }
        };
    try (Pool pool = new Pool(queue, false)) {
      pool.block();
      CheckingTask task = new CheckingTask();
      AgentSpan parent = startSpan("test", "parent");
      try (ContextScope ignored = activateSpan(parent)) {
        pool.executor.execute(wrapped ? () -> task.run() : task);
      } finally {
        parent.finish();
      }
      assertFalse(pool.executor.remove(pool.executor.getQueue().peek()));
      pool.proceed.countDown();
      assertTrue(task.done.await(5, SECONDS));
      assertSame(parent, task.observed);
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfPrevious().operationName("child")));
    }
  }

  @TableTest({
    "Scenario | wrapped",
    "runnable | false  ",
    "lambda   | true   "
  })
  void retryAfterInternalRejectionPreservesContext(boolean wrapped) throws Exception {
    assumeFork();
    try (Pool pool = new Pool(new RetryQueue(), false)) {
      pool.block();
      CheckingTask task = new CheckingTask();
      AgentSpan parent = startSpan("test", "parent");
      try (ContextScope ignored = activateSpan(parent)) {
        pool.executor.execute(wrapped ? () -> task.run() : task);
      } finally {
        parent.finish();
      }
      assertEquals(1, pool.executor.getQueue().size());
      pool.proceed.countDown();
      assertTrue(task.done.await(5, SECONDS));
      assertSame(parent, task.observed);
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("parent"),
              span().childOfPrevious().operationName("child")));
    }
  }

  static void dispatch(ThreadPoolExecutor executor, Runnable task, String operation)
      throws Exception {
    switch (operation) {
      case "execute":
        executor.execute(task);
        break;
      case "lambda":
        executor.execute(() -> task.run());
        break;
      case "submit runnable":
        executor.submit(task);
        break;
      case "submit callable":
        executor.submit(Executors.callable(task));
        break;
      case "invokeAll":
        executor.invokeAll(Collections.singletonList(Executors.callable(task)), 5, SECONDS);
        break;
      case "invokeAny":
        executor.invokeAny(Collections.singletonList(Executors.callable(task)), 5, SECONDS);
        break;
      case "timed execute":
        try {
          ThreadPoolExecutor.class
              .getMethod("execute", Runnable.class, long.class, java.util.concurrent.TimeUnit.class)
              .invoke(executor, task, 0L, SECONDS);
        } catch (InvocationTargetException e) {
          if (e.getCause() instanceof RuntimeException) throw (RuntimeException) e.getCause();
          throw e;
        }
        break;
      default:
        throw new AssertionError(operation);
    }
  }

  static boolean hasTimedExecute() {
    try {
      ThreadPoolExecutor.class.getMethod(
          "execute", Runnable.class, long.class, java.util.concurrent.TimeUnit.class);
      return true;
    } catch (NoSuchMethodException e) {
      return false;
    }
  }

  static void assumeFork() {
    assumeFalse(
        java.util.concurrent.ThreadPoolExecutor.class.isAssignableFrom(ThreadPoolExecutor.class));
  }

  static class CheckingTask implements Runnable {
    final CountDownLatch done = new CountDownLatch(1);
    volatile AgentSpan observed;

    @Override
    public void run() {
      observed = activeSpan();
      AgentSpan child = startSpan("test", "child");
      child.finish();
      done.countDown();
    }
  }

  static class Pool implements AutoCloseable {
    final ThreadPoolExecutor executor;
    final CountDownLatch proceed = new CountDownLatch(1);

    Pool(TaskQueue queue, boolean subclass) {
      executor =
          subclass ? new HookedExecutor(queue) : new ThreadPoolExecutor(1, 1, 5, SECONDS, queue);
      queue.setParent(executor);
      executor.prestartAllCoreThreads();
    }

    void block() throws Exception {
      CountDownLatch entered = new CountDownLatch(1);
      executor.execute(() -> {
        entered.countDown();
        try {
          assertTrue(proceed.await(10, SECONDS));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      assertTrue(entered.await(5, SECONDS));
    }

    @Override
    public void close() throws Exception {
      proceed.countDown();
      executor.shutdown();
      assertTrue(executor.awaitTermination(5, SECONDS));
    }
  }

  static class HookedExecutor extends ThreadPoolExecutor {
    volatile AgentSpan before;
    volatile AgentSpan after;

    HookedExecutor(TaskQueue queue) {
      super(1, 1, 5, SECONDS, queue);
    }

    @Override
    public void execute(Runnable task) {
      super.execute(task);
    }

    @Override
    protected void beforeExecute(Thread thread, Runnable task) {
      super.beforeExecute(thread, task);
      if (activeSpan() != null) before = activeSpan();
    }

    @Override
    protected void afterExecute(Runnable task, Throwable error) {
      if (activeSpan() != null) after = activeSpan();
      super.afterExecute(task, error);
    }
  }

  static class RetryQueue extends TaskQueue {
    @Override
    public boolean offer(Runnable task) {
      return false;
    }
  }
}
