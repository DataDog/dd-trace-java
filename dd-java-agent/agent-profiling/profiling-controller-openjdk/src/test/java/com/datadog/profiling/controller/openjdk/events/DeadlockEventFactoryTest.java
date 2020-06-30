package com.datadog.profiling.controller.openjdk.events;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import jdk.jfr.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeadlockEventFactoryTest {
  private DeadlockEventFactory eventFactory;
  private DeadlockEventFactory disabledDetailsEventFactory;
  private DeadlockEventFactory allDisabledEventFactory;

  @BeforeEach
  void setUp() {
    eventFactory =
        new DeadlockEventFactory() {
          @Override
          boolean isDeadlockEventEnabled() {
            return true;
          }

          @Override
          boolean isDeadlockedThreadEventEnabled() {
            return true;
          }
        };

    disabledDetailsEventFactory =
        new DeadlockEventFactory() {
          @Override
          boolean isDeadlockEventEnabled() {
            return true;
          }

          @Override
          boolean isDeadlockedThreadEventEnabled() {
            return false;
          }
        };

    allDisabledEventFactory =
        new DeadlockEventFactory() {
          @Override
          boolean isDeadlockEventEnabled() {
            return false;
          }

          @Override
          boolean isDeadlockedThreadEventEnabled() {
            return false;
          }
        };
  }

  @Test
  void collectEvents() throws Exception {
    /*
     * All test cases must be done within one test method since there is no way to terminate
     * deadlocked threads and indeterminate execution of test case would be very problematic
     * due to residual state of the previous test cases.
     */
    assertNoDeadlocks();
    int deadlocked = setupMonitorDeadlock();
    assertDeadlock(true, deadlocked);
    deadlocked += setupSynchronizableDeadlock();
    assertDeadlock(false, deadlocked);

    assertEquals(
        1,
        disabledDetailsEventFactory.collectEvents().stream()
            .filter(e -> e instanceof DeadlockEvent)
            .count());
    assertTrue(allDisabledEventFactory.collectEvents().isEmpty());
  }

  private void assertDeadlock(boolean isMonitor, int expected) throws InterruptedException {
    String classifier = isMonitor ? "monitor" : "lock";

    List<? extends Event> events =
        eventFactory.collectEvents().stream()
            .filter(
                e -> {
                  if (e instanceof DeadlockedThreadEvent) {
                    return ((DeadlockedThreadEvent) e).getThreadName().contains(classifier);
                  }
                  return true;
                })
            .sorted(
                (e1, e2) -> {
                  if (e1 instanceof DeadlockEvent) {
                    return e2 instanceof DeadlockEvent ? 0 : -1;
                  } else {
                    if (e2 instanceof DeadlockEvent) {
                      return 1;
                    }
                  }
                  if (e1 instanceof DeadlockedThreadEvent && e2 instanceof DeadlockedThreadEvent) {
                    return ((DeadlockedThreadEvent) e1)
                        .getThreadName()
                        .compareTo(((DeadlockedThreadEvent) e2).getThreadName());
                  }
                  return 0;
                })
            .collect(Collectors.toList());

    assertNotNull(events);
    assertEquals(3, events.size()); // 1 deadlock event + 2 deadlocked thread events in that order
    assertEquals(DeadlockEvent.class, events.get(0).getClass());
    assertEquals(DeadlockedThreadEvent.class, events.get(1).getClass());
    assertEquals(DeadlockedThreadEvent.class, events.get(2).getClass());

    DeadlockEvent deadlockEvent = (DeadlockEvent) events.get(0);
    assertNotEquals(Long.MIN_VALUE, deadlockEvent.getId());
    assertEquals(expected, deadlockEvent.getThreadCount());

    long eventId = deadlockEvent.getId();

    DeadlockedThreadEvent threadEvent1 = (DeadlockedThreadEvent) events.get(1);
    DeadlockedThreadEvent threadEvent2 = (DeadlockedThreadEvent) events.get(2);

    assertEquals(classifier + "-thread-A", threadEvent1.getThreadName());
    assertEquals(classifier + "-thread-B", threadEvent1.getLockOwnerThreadName());
    assertEquals(eventId, threadEvent1.getId());
    assertEquals(threadEvent1.getLockOwnerThreadName(), threadEvent2.getThreadName());
    if (isMonitor) {
      assertNotNull(threadEvent1.getLockingFrame());
    } else {
      assertNull(threadEvent1.getLockingFrame());
    }
    assertNotNull(threadEvent1.getWaitingFrame());

    assertEquals(classifier + "-thread-B", threadEvent2.getThreadName());
    assertEquals(classifier + "-thread-A", threadEvent2.getLockOwnerThreadName());
    assertEquals(eventId, threadEvent2.getId());
    assertEquals(threadEvent1.getLockOwnerThreadName(), threadEvent2.getThreadName());
    if (isMonitor) {
      assertNotNull(threadEvent2.getLockingFrame());
    } else {
      assertNull(threadEvent2.getLockingFrame());
    }
    assertNotNull(threadEvent2.getWaitingFrame());
  }

  private void assertNoDeadlocks() {
    // no deadlocks
    assertTrue(eventFactory.collectEvents().isEmpty());
  }

  private static int setupMonitorDeadlock() throws InterruptedException {
    // it is much easier to provoke a deadlock than mock all the JMX machinery
    Phaser phaser = new Phaser(3);
    Object lockA = new Object();
    Object lockB = new Object();

    Thread threadA =
        new Thread(
            () -> {
              synchronized (lockA) {
                phaser.arriveAndAwaitAdvance(); // sync such as cross-order locking is provoked
                synchronized (lockB) {
                  phaser.arriveAndDeregister(); // virtually unreachable
                }
              }
            },
            "monitor-thread-A");
    Thread threadB =
        new Thread(
            () -> {
              synchronized (lockB) {
                phaser.arriveAndAwaitAdvance(); // sync such as cross-order locking is provoked
                synchronized (lockA) {
                  phaser.arriveAndDeregister(); // virtually unreachable
                }
              }
            },
            "monitor-thread-B");
    threadA.setDaemon(true);
    threadB.setDaemon(true);

    CountDownLatch latch = new CountDownLatch(1);
    Thread main =
        new Thread(
            () -> {
              threadA.start();
              threadB.start();
              phaser.arriveAndAwaitAdvance(); // enter deadlock
              phaser.arriveAndAwaitAdvance(); // unreachable if deadlock is present
              latch.countDown();
            },
            "main-monitor-thread");
    main.setDaemon(true);

    main.start();

    if (latch.await(500, TimeUnit.MILLISECONDS)) {
      fail("Unable to create deadlock");
    }
    return 2;
  }

  private static int setupSynchronizableDeadlock() throws InterruptedException {
    // it is much easier to provoke a deadlock than mock all the JMX machinery
    Phaser phaser = new Phaser(3);
    Lock lockA = new ReentrantLock();
    Lock lockB = new ReentrantLock();

    Thread threadA =
        new Thread(
            () -> {
              lockA.lock();
              try {
                phaser.arriveAndAwaitAdvance(); // sync such as cross-order locking is provoked
                lockB.lock();
                try {
                  phaser.arriveAndDeregister(); // virtually unreachable
                } finally {
                  lockB.unlock();
                }
              } finally {
                lockA.unlock();
              }
            },
            "lock-thread-A");
    Thread threadB =
        new Thread(
            () -> {
              lockB.lock();
              try {
                phaser.arriveAndAwaitAdvance(); // sync such as cross-order locking is provoked
                lockA.lock();
                try {
                  phaser.arriveAndDeregister(); // virtually unreachable
                } finally {
                  lockA.unlock();
                }
              } finally {
                lockB.unlock();
              }
            },
            "lock-thread-B");
    threadA.setDaemon(true);
    threadB.setDaemon(true);

    CountDownLatch latch = new CountDownLatch(1);
    Thread main =
        new Thread(
            () -> {
              threadA.start();
              threadB.start();
              phaser.arriveAndAwaitAdvance(); // enter deadlock
              phaser.arriveAndAwaitAdvance(); // unreachable if deadlock is present
              latch.countDown();
            },
            "main-lock-thread");
    main.setDaemon(true);

    main.start();

    if (latch.await(500, TimeUnit.MILLISECONDS)) {
      fail("Unable to create deadlock");
    }

    return 2;
  }
}
