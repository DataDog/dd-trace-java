package datadog.trace.test.util;

import groovy.lang.Closure;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ThreadUtils {

  /**
   * Utility to easily run a Closure in parallel, i.e. in spock like this:
   *
   * <pre>{@code
   * def "some test"() {
   *   expect:
   *   runConcurrently(10, 100, {
   *     def something = ...
   *     def other = ...
   *     assert something == other
   *   })
   * }
   * }</pre>
   *
   * Writing a spock extension was investigated, but it is not possible to run an Invocation
   * multiple times concurrently since a lot of the spock internal state and mock scoping is not
   * thread safe.
   *
   * @param concurrency the number of concurrent invocations
   * @param totalInvocations the total number of invocations
   * @param closure the closure to run
   * @return true if everything went well
   * @throws Throwable if anything went wrong
   */
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
