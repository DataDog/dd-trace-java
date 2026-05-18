package datadog.trace.api.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ScaReachabilityDependencyRegistryTest {

  @BeforeEach
  void setUp() {
    ScaReachabilityDependencyRegistry.INSTANCE.resetForTesting();
  }

  @AfterEach
  void tearDown() {
    ScaReachabilityDependencyRegistry.INSTANCE.resetForTesting();
  }

  /**
   * Regression test for the first-hit-wins thread-safety race.
   *
   * <p>Before the fix (volatile + check-then-set), two threads calling {@code recordHit} for the
   * same CVE from different methods could both observe {@code hit == null} simultaneously and both
   * write, with the second overwriting the first. The fix uses {@link
   * java.util.concurrent.atomic.AtomicReference#compareAndSet} to guarantee exactly one thread
   * wins.
   *
   * <p>This test starts N threads simultaneously, each recording a hit for the same CVE but from a
   * different callsite. After all threads complete, exactly one callsite must be recorded.
   */
  @Test
  void recordHit_concurrentCallsForSameCve_exactlyOneCallsiteStored() throws InterruptedException {
    int threadCount = 20;
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("com.example:lib", "1.0.0", "GHSA-test");

    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    ExecutorService pool = Executors.newFixedThreadPool(threadCount);

    for (int i = 0; i < threadCount; i++) {
      final int idx = i;
      pool.submit(
          () -> {
            try {
              startLatch.await(); // wait until all threads are ready
              ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
                  "com.example:lib",
                  "1.0.0",
                  "GHSA-test",
                  "com.myapp.Controller" + idx,
                  "method" + idx,
                  idx);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown(); // release all threads simultaneously
    doneLatch.await(10, TimeUnit.SECONDS);
    pool.shutdown();

    List<ScaReachabilityDependencyRegistry.DependencySnapshot> snapshots =
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();

    assertEquals(1, snapshots.size(), "exactly one dep snapshot");
    ScaReachabilityDependencyRegistry.DependencySnapshot dep = snapshots.get(0);
    assertEquals(1, dep.cves.size(), "exactly one CVE");

    ScaReachabilityDependencyRegistry.CveSnapshot cve = dep.cves.get(0);
    assertNotNull(cve.hit, "exactly one hit must have been recorded");

    // Verify the recorded callsite is one of the N valid options
    String recordedClass = cve.hit.className();
    String recordedSymbol = cve.hit.symbolName();
    boolean isValidCallsite = false;
    for (int i = 0; i < threadCount; i++) {
      if (("com.myapp.Controller" + i).equals(recordedClass)
          && ("method" + i).equals(recordedSymbol)) {
        isValidCallsite = true;
        break;
      }
    }
    assertEquals(
        true,
        isValidCallsite,
        "recorded callsite must be one of the " + threadCount + " valid options");
  }
}
