package datadog.smoketest.profiling;

import datadog.trace.api.Trace;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;

public class ProfilingTestApplication {
  private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();

  public static void main(final String[] args) throws InterruptedException {
    long duration = -1;
    if (args.length > 0) {
      duration = TimeUnit.SECONDS.toMillis(Long.parseLong(args[0]));
    }
    setupDeadlock();
    final long startTime = System.currentTimeMillis();
    while (true) {
      tracedMethod();
      if (duration > 0 && duration + startTime < System.currentTimeMillis()) {
        break;
      }
    }
    System.out.println("Exiting (" + duration + ")");
  }

  @Trace
  @SuppressFBWarnings("DM_GC")
  private static void tracedMethod() throws InterruptedException {
    System.out.println("Tracing");
    tracedBusyMethod();
    // request GC which will in turn trigger OldObjectSample events
    System.gc();
    try {
      throw new IllegalStateException("test");
    } catch (final IllegalStateException ignored) {
    }
    Thread.sleep(50);
  }

  @Trace
  private static void tracedBusyMethod() {
    long startTime = THREAD_MX_BEAN.getCurrentThreadCpuTime();
    Random random = new Random();
    long accumulator = 0L;
    while (true) {
      accumulator += random.nextInt(113);
      if (THREAD_MX_BEAN.getCurrentThreadCpuTime() - startTime > 10_000_000L) {
        // looking for at least 10ms CPU time
        break;
      }
    }
    System.out.println("accumulated: " + accumulator);
  }

  private static void setupDeadlock() {
    final Phaser phaser = new Phaser(3);
    final Object lockA = new Object();
    final Object lockB = new Object();

    final Thread threadA =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                synchronized (lockA) {
                  phaser.arriveAndAwaitAdvance(); // sync such as cross-order locking is provoked
                  synchronized (lockB) {
                    phaser.arriveAndDeregister(); // virtually unreachable
                  }
                }
              }
            },
            "monitor-thread-A");
    final Thread threadB =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                synchronized (lockB) {
                  phaser.arriveAndAwaitAdvance(); // sync such as cross-order locking is provoked
                  synchronized (lockA) {
                    phaser.arriveAndDeregister(); // virtually unreachable
                  }
                }
              }
            },
            "monitor-thread-B");
    threadA.setDaemon(true);
    threadB.setDaemon(true);

    final CountDownLatch latch = new CountDownLatch(1);
    Thread main =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                threadA.start();
                threadB.start();
                phaser.arriveAndAwaitAdvance(); // enter deadlock
                phaser.arriveAndAwaitAdvance(); // unreachable if deadlock is present
                latch.countDown();
              }
            },
            "main-monitor-thread");
    main.setDaemon(true);

    main.start();
  }
}
