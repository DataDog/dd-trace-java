package datadog.smoketest.concurrent;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class VirtualThreadTimeoutStartup {
  public static void main(String[] args) throws InterruptedException {
    Thread thread =
        Thread.startVirtualThread(() -> LockSupport.parkNanos(TimeUnit.DAYS.toNanos(1)));

    awaitTimedWaiting(thread);
    LockSupport.unpark(thread);
    thread.join(10_000);
    if (thread.isAlive()) {
      throw new AssertionError("Virtual thread did not resume after unpark");
    }
  }

  private static void awaitTimedWaiting(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (thread.getState() != Thread.State.TIMED_WAITING && thread.isAlive()) {
      if (System.nanoTime() >= deadline) {
        throw new AssertionError("Virtual thread did not enter a timed park");
      }
      Thread.yield();
    }
    if (thread.getState() != Thread.State.TIMED_WAITING) {
      throw new AssertionError("Virtual thread exited before entering a timed park");
    }
  }
}
