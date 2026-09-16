package datadog.trace.test.util;

import groovy.lang.Closure;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ThreadUtils {

  /** A {@link Runnable} whose {@link #run()} may throw a checked exception. */
  @FunctionalInterface
  public interface ThrowingRunnable {
    void run() throws Throwable;
  }

  /**
   * Utility to easily run a piece of code in parallel, e.g. in a JUnit test like this:
   *
   * <pre>{@code
   * runConcurrently(
   *     10,
   *     100,
   *     () -> {
   *       Object something = computeSomething();
   *       Object other = computeOther();
   *       assertEquals(something, other);
   *     });
   * }</pre>
   *
   * @param concurrency the number of concurrent invocations
   * @param totalInvocations the total number of invocations
   * @param runnable the code to run
   * @return {@code true} if everything went well
   * @throws Throwable if anything went wrong
   */
  public static boolean runConcurrently(
      final int concurrency, final int totalInvocations, final ThrowingRunnable runnable)
      throws Throwable {
    return runConcurrently(
        concurrency,
        totalInvocations,
        new Closure<Void>(null) {
          @Override
          public Void call() {
            try {
              runnable.run();
            } catch (RuntimeException | Error e) {
              throw e;
            } catch (Throwable t) {
              throw new RuntimeException(t);
            }
            return null;
          }
        });
  }

  public static boolean runConcurrently(
      final int concurrency, final int totalInvocations, final Closure<Void> closure)
      throws Throwable {
    // There is no reason in creating more threads than invocations
    int poolSize = Math.min(concurrency, totalInvocations);

    // If we are not running anything concurrently, then just call the closure directly
    if (poolSize == 1) {
      for (int c = 0; c < totalInvocations; c++) {
        closure.call();
      }
      return true;
    }

    ExecutorService executor = Executors.newFixedThreadPool(poolSize);
    final AtomicReference<Throwable> throwable = new AtomicReference<>();
    int each = totalInvocations / poolSize;
    int remainder = totalInvocations % poolSize;
    final CountDownLatch startBarrier = new CountDownLatch(poolSize + 1);
    final CountDownLatch endBarrier = new CountDownLatch(poolSize + 1);
    for (int i = 0; i < poolSize; i++) {
      final int invocations = each + (remainder <= 0 ? 0 : 1);
      executor.execute(
          () -> {
            try {
              startBarrier.countDown();
              startBarrier.await();
              for (int c = 0; c < invocations && throwable.get() == null; c++) {
                try {
                  closure.call();
                } catch (Throwable t) {
                  throwable.compareAndSet(null, t);
                }
              }
            } catch (Throwable t) {
              throwable.compareAndSet(null, t);
            } finally {
              try {
                endBarrier.countDown();
              } catch (Throwable t) {
                throwable.compareAndSet(null, t);
              }
            }
          });
      remainder--;
    }
    startBarrier.countDown();
    startBarrier.await();
    endBarrier.countDown();
    endBarrier.await();
    executor.shutdownNow();
    executor.awaitTermination(30, TimeUnit.SECONDS);
    Throwable t = throwable.get();
    if (t != null) {
      throw t;
    }
    return true;
  }
}
