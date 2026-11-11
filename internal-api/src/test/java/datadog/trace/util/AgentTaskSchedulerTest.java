package datadog.trace.util;

import static datadog.trace.util.AgentThreadFactory.AgentThread.TASK_SCHEDULER;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.util.GCUtils;
import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentTaskSchedulerTest {

  private AgentTaskScheduler scheduler;

  @BeforeEach
  void setup() {
    scheduler = new AgentTaskScheduler(TASK_SCHEDULER);
  }

  @AfterEach
  void cleanup() {
    scheduler.shutdown(10, MILLISECONDS);
  }

  @Test
  void testScheduling() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(2);
    AgentTaskScheduler.Task<CountDownLatch> task =
        new AgentTaskScheduler.Task<CountDownLatch>() {
          @Override
          public void run(CountDownLatch target) {
            target.countDown();
          }
        };

    assertFalse(scheduler.isShutdown());

    scheduler.scheduleAtFixedRate(task, latch, 50, 10, MILLISECONDS);

    assertTrue(latch.await(500, MILLISECONDS));
  }

  // @Flaky("awaitGC is flaky")
  @Test
  void testWeakScheduling() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(Integer.MAX_VALUE);
    WeakReference<CountDownLatch> weakLatch = new WeakReference<>(latch);
    AgentTaskScheduler.Task<CountDownLatch> task =
        new AgentTaskScheduler.Task<CountDownLatch>() {
          @Override
          public void run(CountDownLatch target) {
            target.countDown();
          }
        };

    assertFalse(scheduler.isShutdown());

    scheduler.weakScheduleAtFixedRate(task, latch, 10, 10, MILLISECONDS);
    latch = null;

    GCUtils.awaitGC(weakLatch);
    Thread.sleep(100);
    assertEquals(0, scheduler.taskCount());
  }

  @Test
  void testDelay() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    AgentTaskScheduler.Task<CountDownLatch> task =
        new AgentTaskScheduler.Task<CountDownLatch>() {
          @Override
          public void run(CountDownLatch target) {
            target.countDown();
          }
        };

    assertFalse(scheduler.isShutdown());

    scheduler.schedule(task, latch, 10, MILLISECONDS);

    assertTrue(latch.await(500, MILLISECONDS));
    assertEquals(0, scheduler.taskCount());
  }

  @Test
  void testFixedDelay() throws InterruptedException {
    CountDownLatch workerStarted = new CountDownLatch(1);
    CountDownLatch releaseWorker = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(3);

    long[] starts = new long[3];
    long[] finishes = new long[3];

    AgentTaskScheduler.Task<CountDownLatch> task =
        new AgentTaskScheduler.Task<CountDownLatch>() {
          private int execution;

          @Override
          public void run(CountDownLatch target) {
            if (execution == starts.length) {
              return;
            }

            int index = execution++;
            starts[index] = System.nanoTime();

            try {
              Thread.sleep(100);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              return;
            }

            finishes[index] = System.nanoTime();
            target.countDown();
          }
        };

    assertFalse(scheduler.isShutdown());

    scheduler.execute(() -> {
      workerStarted.countDown();

      try {
        releaseWorker.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    });

    AgentTaskScheduler.Scheduled<CountDownLatch> scheduled = null;
    try {
      assertTrue(workerStarted.await(10, SECONDS), "Scheduler worker did not start");

      scheduled = scheduler.scheduleWithFixedDelay(task, completed, 0, 200, MILLISECONDS);

      // Keep the worker blocked so it cannot take the zero-delay task before taskCount() runs.
      assertEquals(1, scheduler.taskCount());

      releaseWorker.countDown();

      assertTrue(completed.await(10, SECONDS), "Periodic task did not complete three executions");

      for (int i = 1; i < starts.length; i++) {
        long delay = starts[i] - finishes[i - 1];
        assertTrue(
            delay >= MILLISECONDS.toNanos(200),
            "Execution " + (i + 1) + " started too soon after completion: " + delay + " ns");
      }
    } finally {
      if (scheduled != null) {
        scheduled.cancel();
      }

      releaseWorker.countDown();
    }
  }

  @Test
  void testCancel() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(Integer.MAX_VALUE);
    AgentTaskScheduler.Task<CountDownLatch> task =
        new AgentTaskScheduler.Task<CountDownLatch>() {
          @Override
          public void run(CountDownLatch target) {
            target.countDown();
          }
        };

    assertFalse(scheduler.isShutdown());

    AgentTaskScheduler.Scheduled<CountDownLatch> scheduled =
        scheduler.scheduleAtFixedRate(task, latch, 10, 10, MILLISECONDS);

    scheduled.cancel();
    Thread.sleep(100);
    assertEquals(0, scheduler.taskCount());
  }

  @Test
  void testExecute() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    Runnable target =
        new Runnable() {
          @Override
          public void run() {
            latch.countDown();
          }
        };

    assertFalse(scheduler.isShutdown());

    scheduler.execute(target);

    assertTrue(latch.await(500, MILLISECONDS));
    assertEquals(0, scheduler.taskCount());
  }

  @Test
  void testShutdown() {
    CountDownLatch latch = new CountDownLatch(Integer.MAX_VALUE);
    AgentTaskScheduler.Task<CountDownLatch> task =
        new AgentTaskScheduler.Task<CountDownLatch>() {
          @Override
          public void run(CountDownLatch target) {
            target.countDown();
          }
        };

    assertFalse(scheduler.isShutdown());

    scheduler.scheduleAtFixedRate(task, latch, 10, 10, MILLISECONDS);

    scheduler.shutdown(1, SECONDS);
    assertTrue(scheduler.isShutdown());
    assertEquals(0, scheduler.taskCount());
  }

  @Test
  void testNullTarget() {
    AtomicInteger callCount = new AtomicInteger();
    AgentTaskScheduler.Task<Object> task =
        new AgentTaskScheduler.Task<Object>() {
          @Override
          public void run(Object target) {
            callCount.incrementAndGet();
          }
        };

    assertFalse(scheduler.isShutdown());

    scheduler.scheduleAtFixedRate(task, null, 10, 10, MILLISECONDS);

    assertEquals(0, scheduler.taskCount());
    assertEquals(0, callCount.get());
  }
}
