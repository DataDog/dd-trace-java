package executor;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.Wrapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ReusableRunnableSubmissionTest extends AbstractInstrumentationTest {

  private static final Object TEST_CONTEXT = ContextKey.named("reusable-runnable-test");

  @Test
  void overlappingSubmissionsKeepTheirContextsAndCommonCaseIdentity() throws Exception {
    BlockingTask blocker = new BlockingTask();
    RecordingTask shared = new RecordingTask(2);
    ThreadPoolExecutor pool = newPool(3);
    try {
      pool.execute(blocker);
      assertTrue(blocker.started.await(10, TimeUnit.SECONDS));
      submit(pool, shared, "first");
      submit(pool, shared, "second");

      Object[] queued = pool.getQueue().toArray();
      assertSame(shared, queued[0]);
      assertTrue(queued[1] instanceof Wrapper);

      blocker.release.countDown();
      assertTrue(shared.finished.await(10, TimeUnit.SECONDS));
      assertEquals(asList("first", "second"), shared.observed);
    } finally {
      blocker.release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void directRunCannotStealQueuedSubmissionContext() throws Exception {
    BlockingTask blocker = new BlockingTask();
    RecordingTask shared = new RecordingTask(2);
    ThreadPoolExecutor pool = newPool(2);
    try {
      pool.execute(blocker);
      assertTrue(blocker.started.await(10, TimeUnit.SECONDS));
      submit(pool, shared, "queued");

      shared.run();
      blocker.release.countDown();

      assertTrue(shared.finished.await(10, TimeUnit.SECONDS));
      assertEquals(asList(null, "queued"), shared.observed);
    } finally {
      blocker.release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void callerRunsUsesRejectedCollisionContext() throws Exception {
    BlockingTask blocker = new BlockingTask();
    RecordingTask shared = new RecordingTask(2);
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),
            new ThreadPoolExecutor.CallerRunsPolicy());
    try {
      pool.execute(blocker);
      assertTrue(blocker.started.await(10, TimeUnit.SECONDS));
      submit(pool, shared, "queued");
      submit(pool, shared, "rejected");

      blocker.release.countDown();
      assertTrue(shared.finished.await(10, TimeUnit.SECONDS));
      assertEquals(asList("rejected", "queued"), shared.observed);
    } finally {
      blocker.release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void removeReleasesTheExactSubmissionSlot() throws Exception {
    BlockingTask blocker = new BlockingTask();
    RecordingTask shared = new RecordingTask(1);
    ThreadPoolExecutor pool = newPool(2);
    try {
      pool.execute(blocker);
      assertTrue(blocker.started.await(10, TimeUnit.SECONDS));
      submit(pool, shared, "removed");
      assertTrue(pool.remove(shared));

      submit(pool, shared, "replacement");
      assertSame(shared, pool.getQueue().peek());
      blocker.release.countDown();

      assertTrue(shared.finished.await(10, TimeUnit.SECONDS));
      assertEquals(Collections.singletonList("replacement"), shared.observed);
    } finally {
      blocker.release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void overlappingSubmissionsCanBeRemovedBySubmittedIdentity() throws Exception {
    BlockingTask blocker = new BlockingTask();
    RecordingTask shared = new RecordingTask(2);
    ThreadPoolExecutor pool = newPool(2);
    try {
      pool.execute(blocker);
      assertTrue(blocker.started.await(10, TimeUnit.SECONDS));
      submit(pool, shared, "first");
      submit(pool, shared, "second");

      assertTrue(pool.remove(shared));
      assertTrue(pool.remove(shared));
      assertTrue(pool.getQueue().isEmpty());
    } finally {
      blocker.release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void removeSelectsFirstLogicalOccurrenceAfterStateIsRecycled() throws Exception {
    BlockingTask blocker = new BlockingTask();
    RecyclingTask shared = new RecyclingTask();
    ThreadPoolExecutor pool = newPool(3);
    try {
      pool.execute(blocker);
      assertTrue(blocker.started.await(10, TimeUnit.SECONDS));
      submit(pool, shared, "running");
      submit(pool, shared, "wrapped");

      blocker.release.countDown();
      assertTrue(shared.firstStarted.await(10, TimeUnit.SECONDS));
      submit(pool, shared, "recycled");

      Object[] queued = pool.getQueue().toArray();
      assertTrue(queued[0] instanceof Wrapper);
      assertSame(shared, queued[1]);
      assertTrue(pool.remove(shared));
      assertSame(shared, pool.getQueue().peek());

      shared.releaseFirst.countDown();
      assertTrue(shared.finished.await(10, TimeUnit.SECONDS));
      assertEquals(asList("running", "recycled"), shared.observed);
    } finally {
      blocker.release.countDown();
      shared.releaseFirst.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void priorityQueueCollisionPreservesApplicationBehavior() throws Exception {
    BlockingTask blocker = new BlockingTask();
    PriorityTask shared = new PriorityTask();
    ThreadPoolExecutor pool =
        new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new PriorityBlockingQueue<Runnable>());
    try {
      pool.execute(blocker);
      assertTrue(blocker.started.await(10, TimeUnit.SECONDS));
      submit(pool, shared, "first");
      submit(pool, shared, "not-propagated");

      Object[] queued = pool.getQueue().toArray();
      assertSame(shared, queued[0]);
      assertSame(shared, queued[1]);
      blocker.release.countDown();

      assertTrue(shared.finished.await(10, TimeUnit.SECONDS));
      assertEquals(asList("first", null), shared.observed);
    } finally {
      blocker.release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void shutdownNowReturnsSubmittedIdentity() throws Exception {
    BlockingTask blocker = new BlockingTask();
    RecordingTask shared = new RecordingTask(2);
    ThreadPoolExecutor pool = newPool(2);
    try {
      pool.execute(blocker);
      assertTrue(blocker.started.await(10, TimeUnit.SECONDS));
      submit(pool, shared, "first");
      submit(pool, shared, "second");

      List<Runnable> returned = pool.shutdownNow();
      assertEquals(2, returned.size());
      assertSame(shared, returned.get(0));
      assertSame(shared, returned.get(1));
    } finally {
      blocker.release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void decoratingSubclassPropagatesAfterDecoration() throws Exception {
    RecordingTask submitted = new RecordingTask(1);
    DecoratingExecutor pool = new DecoratingExecutor();
    try {
      submit(pool, submitted, "decorated");
      assertTrue(submitted.finished.await(10, TimeUnit.SECONDS));
      assertEquals(Collections.singletonList("decorated"), submitted.observed);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void delegatingSubclassKeepsPerSubmissionOwnership() throws Exception {
    BlockingTask blocker = new BlockingTask();
    RecordingTask shared = new RecordingTask(2);
    ThreadPoolExecutor pool = new DelegatingExecutor();
    try {
      pool.execute(blocker);
      assertTrue(blocker.started.await(10, TimeUnit.SECONDS));
      submit(pool, shared, "first");
      submit(pool, shared, "second");

      Object[] queued = pool.getQueue().toArray();
      assertSame(shared, queued[0]);
      assertTrue(queued[1] instanceof Wrapper);

      blocker.release.countDown();
      assertTrue(shared.finished.await(10, TimeUnit.SECONDS));
      assertEquals(asList("first", "second"), shared.observed);
    } finally {
      blocker.release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void nonDelegatingSubclassKeepsNamedTaskPropagation() throws Exception {
    RecordingTask submitted = new RecordingTask(1);
    NonDelegatingExecutor pool = new NonDelegatingExecutor();
    try {
      submit(pool, submitted, "custom");

      assertTrue(submitted.finished.await(10, TimeUnit.SECONDS));
      assertEquals(Collections.singletonList("custom"), submitted.observed);
    } finally {
      pool.stopWorker();
      pool.shutdownNow();
    }
  }

  @Test
  void nonDelegatingSubclassKeepsLambdaPropagation() throws Exception {
    List<AgentSpan> observed = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch finished = new CountDownLatch(1);
    Runnable submitted =
        () -> {
          observed.add(activeSpan());
          finished.countDown();
        };
    NonDelegatingExecutor pool = new NonDelegatingExecutor();
    AgentSpan parent = startSpan("test", "lambda-parent");
    try {
      try (ContextScope ignored = activateSpan(parent)) {
        pool.execute(submitted);
        assertTrue(finished.await(10, TimeUnit.SECONDS));
      }

      assertEquals(Collections.singletonList(parent), observed);
    } finally {
      parent.finish();
      pool.stopWorker();
      pool.shutdownNow();
    }
  }

  private static ThreadPoolExecutor newPool(int queueCapacity) {
    return new ThreadPoolExecutor(
        1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queueCapacity));
  }

  private static void submit(ThreadPoolExecutor pool, Runnable task, String value) {
    try (ContextScope ignored = Context.root().with(castContextKey(TEST_CONTEXT), value).attach()) {
      pool.execute(task);
    }
  }

  private static List<String> asList(String first, String second) {
    List<String> values = new ArrayList<>(2);
    values.add(first);
    values.add(second);
    return values;
  }

  @SuppressWarnings("unchecked")
  private static <T> T castContextKey(Object key) {
    return (T) key;
  }

  private static final class RecordingTask implements Runnable {
    private final List<String> observed = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch finished;

    private RecordingTask(int executions) {
      finished = new CountDownLatch(executions);
    }

    @Override
    public void run() {
      observed.add(Context.current().get(castContextKey(TEST_CONTEXT)));
      finished.countDown();
    }
  }

  private static final class BlockingTask implements Runnable {
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public void run() {
      started.countDown();
      try {
        release.await(10, TimeUnit.SECONDS);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static final class RecyclingTask implements Runnable {
    private final List<String> observed = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger executions = new AtomicInteger();
    private final CountDownLatch firstStarted = new CountDownLatch(1);
    private final CountDownLatch releaseFirst = new CountDownLatch(1);
    private final CountDownLatch finished = new CountDownLatch(2);

    @Override
    public void run() {
      if (executions.getAndIncrement() == 0) {
        firstStarted.countDown();
        try {
          releaseFirst.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      observed.add(Context.current().get(castContextKey(TEST_CONTEXT)));
      finished.countDown();
    }
  }

  private static final class PriorityTask implements Runnable, Comparable<PriorityTask> {
    private final List<String> observed = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch finished = new CountDownLatch(2);

    @Override
    public void run() {
      observed.add(Context.current().get(castContextKey(TEST_CONTEXT)));
      finished.countDown();
    }

    @Override
    public int compareTo(PriorityTask ignored) {
      return 0;
    }
  }

  private static final class DecoratingExecutor extends ThreadPoolExecutor {
    private DecoratingExecutor() {
      super(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
    }

    @Override
    public void execute(Runnable command) {
      super.execute(new DelegatingTask(command));
    }
  }

  private static final class DelegatingExecutor extends ThreadPoolExecutor {
    private DelegatingExecutor() {
      super(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(3));
    }

    @Override
    public void execute(Runnable command) {
      super.execute(command);
    }
  }

  private static final class NonDelegatingExecutor extends ThreadPoolExecutor {
    private final BlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final Thread worker;

    private NonDelegatingExecutor() {
      super(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
      worker = new Thread(this::runTasks, "non-delegating-executor-test");
      worker.setDaemon(true);
      worker.start();
    }

    @Override
    public void execute(Runnable command) {
      tasks.add(command);
    }

    private void runTasks() {
      try {
        while (!Thread.currentThread().isInterrupted()) {
          tasks.take().run();
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    private void stopWorker() throws InterruptedException {
      worker.interrupt();
      worker.join(TimeUnit.SECONDS.toMillis(10));
    }
  }

  private static final class DelegatingTask implements Runnable {
    private final Runnable delegate;

    private DelegatingTask(Runnable delegate) {
      this.delegate = delegate;
    }

    @Override
    public void run() {
      delegate.run();
    }
  }
}
