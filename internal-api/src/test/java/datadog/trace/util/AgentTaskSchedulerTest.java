package datadog.trace.util;

import static datadog.trace.util.AgentThreadFactory.AgentThread.TASK_SCHEDULER;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.test.util.DDJavaSpecification;
import datadog.trace.test.util.GCUtils;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentTaskSchedulerTest extends DDJavaSpecification {

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

  private static boolean delta(long first, long second, float ratio, long expected) {
    long difference = first - second;
    long interval = (long) (difference * ratio);
    return difference >= expected - interval && difference <= expected + interval;
  }

  @Test
  void testFixedDelay() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(3);
    List<Long> timestamps = new ArrayList<>();
    AgentTaskScheduler.Task<CountDownLatch> task =
        new AgentTaskScheduler.Task<CountDownLatch>() {
          @Override
          public void run(CountDownLatch target) {
            timestamps.add(System.nanoTime());
            try {
              Thread.sleep(100);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            target.countDown();
          }
        };

    assertFalse(scheduler.isShutdown());

    scheduler.scheduleWithFixedDelay(task, latch, 0, 200, MILLISECONDS);
    assertEquals(1, scheduler.taskCount());

    assertTrue(latch.await(1000, MILLISECONDS));
    assertTrue(
        delta(timestamps.get(1), timestamps.get(0), 0.15f, NANOSECONDS.convert(300, MILLISECONDS)));
    assertTrue(
        delta(timestamps.get(2), timestamps.get(1), 0.15f, NANOSECONDS.convert(300, MILLISECONDS)));
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
