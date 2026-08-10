package datadog.trace.api.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Config;
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
    assertTrue(
        isValidCallsite, "recorded callsite must be one of the " + threadCount + " valid options");
  }

  @Test
  void registerCve_addsEntryAndMarksPending() {
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("com.example:lib", "2.0.0", "GHSA-0001");

    List<ScaReachabilityDependencyRegistry.DependencySnapshot> snapshots =
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();

    assertEquals(1, snapshots.size());
    ScaReachabilityDependencyRegistry.DependencySnapshot dep = snapshots.get(0);
    assertEquals("com.example:lib", dep.artifact);
    assertEquals("2.0.0", dep.version);
    assertEquals(1, dep.cves.size());
    assertEquals("GHSA-0001", dep.cves.get(0).vulnId);
    assertNull(dep.cves.get(0).hit, "class-load registration has no callsite yet");
  }

  @Test
  void recordHit_snapshotContainsFullHitMetadata() {
    ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
        "com.example:lib", "2.0.0", "GHSA-0001", "com.myapp.Ctrl", "handle", 42);

    List<ScaReachabilityDependencyRegistry.DependencySnapshot> snapshots =
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();

    assertEquals(1, snapshots.size());
    ScaReachabilityDependencyRegistry.DependencySnapshot dep = snapshots.get(0);
    assertEquals("com.example:lib", dep.artifact);
    assertEquals("2.0.0", dep.version);
    assertEquals(1, dep.cves.size());

    ScaReachabilityDependencyRegistry.CveSnapshot cve = dep.cves.get(0);
    assertEquals("GHSA-0001", cve.vulnId);
    assertNotNull(cve.hit);
    assertEquals("GHSA-0001", cve.hit.vulnId());
    assertEquals("com.example:lib", cve.hit.artifact());
    assertEquals("2.0.0", cve.hit.version());
    assertEquals("com.myapp.Ctrl", cve.hit.className());
    assertEquals("handle", cve.hit.symbolName());
    assertEquals(42, cve.hit.line());
  }

  @Test
  void peekSnapshot_returnsCurrentStateWithoutClearingPendingFlag() {
    assertNull(ScaReachabilityDependencyRegistry.INSTANCE.peekSnapshot("missing", "1.0.0"));

    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("com.example:lib", "2.0.0", "GHSA-0001");
    ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
        "com.example:lib", "2.0.0", "GHSA-0001", "com.myapp.Ctrl", "handle", 42);

    ScaReachabilityDependencyRegistry.DependencySnapshot peeked =
        ScaReachabilityDependencyRegistry.INSTANCE.peekSnapshot("com.example:lib", "2.0.0");

    assertNotNull(peeked);
    assertEquals("com.example:lib", peeked.artifact);
    assertEquals("2.0.0", peeked.version);
    assertEquals(1, peeked.cves.size());
    assertEquals("GHSA-0001", peeked.cves.get(0).vulnId);
    assertNotNull(peeked.cves.get(0).hit);

    List<ScaReachabilityDependencyRegistry.DependencySnapshot> snapshots =
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();
    assertEquals(1, snapshots.size(), "peek must not clear pending state");
  }

  @Test
  void drainPendingDependencies_secondDrainEmpty_untilNewHit() {
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("com.example:lib", "2.0.0", "GHSA-0001");

    // First drain returns the pending dep
    List<ScaReachabilityDependencyRegistry.DependencySnapshot> first =
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();
    assertEquals(1, first.size());

    // Second drain with no new state change returns empty
    List<ScaReachabilityDependencyRegistry.DependencySnapshot> second =
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();
    assertTrue(second.isEmpty(), "no pending changes since last drain");

    // A new hit marks the dep pending again
    ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
        "com.example:lib", "2.0.0", "GHSA-0001", "com.myapp.Ctrl", "handle", 42);
    List<ScaReachabilityDependencyRegistry.DependencySnapshot> third =
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();
    assertEquals(1, third.size(), "dep must be pending again after a hit");
    assertNotNull(third.get(0).cves.get(0).hit, "hit callsite must be recorded");
  }

  @Test
  void recordHit_firstHitWinsAndDuplicateDoesNotMarkPendingAgain() {
    ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
        "com.example:lib", "2.0.0", "GHSA-0001", "com.myapp.First", "first", 1);
    ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();

    ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
        "com.example:lib", "2.0.0", "GHSA-0001", "com.myapp.Second", "second", 2);

    assertTrue(
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies().isEmpty(),
        "duplicate hit for the same CVE must not mark the dependency pending");

    ScaReachabilityDependencyRegistry.DependencySnapshot snapshot =
        ScaReachabilityDependencyRegistry.INSTANCE.peekSnapshot("com.example:lib", "2.0.0");
    assertNotNull(snapshot);
    assertEquals("com.myapp.First", snapshot.cves.get(0).hit.className());
    assertEquals("first", snapshot.cves.get(0).hit.symbolName());
  }

  @Test
  void registerCve_atCap_newKeysRejected() {
    int cap = Config.get().getAppSecScaMaxTrackedDependencies();

    // Fill registry to cap
    for (int i = 0; i < cap; i++) {
      ScaReachabilityDependencyRegistry.INSTANCE.registerCve("art" + i, "1.0", "GHSA-" + i);
    }

    // One more unique key — must be rejected
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("art-over-cap", "1.0", "GHSA-over");

    List<ScaReachabilityDependencyRegistry.DependencySnapshot> snapshots =
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();
    assertEquals(cap, snapshots.size(), "registry must not exceed cap");
    boolean found = snapshots.stream().anyMatch(s -> s.artifact.equals("art-over-cap"));
    assertFalse(found, "over-cap dep must be rejected");
  }

  @Test
  void registerCve_atCap_existingKeyStillUpdated() {
    int cap = Config.get().getAppSecScaMaxTrackedDependencies();

    // Fill registry to cap
    for (int i = 0; i < cap; i++) {
      ScaReachabilityDependencyRegistry.INSTANCE.registerCve("art" + i, "1.0", "GHSA-" + i);
    }
    ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();

    // Adding a NEW CVE to an EXISTING key must still succeed (key already present, cap not
    // exceeded)
    ScaReachabilityDependencyRegistry.INSTANCE.registerCve("art0", "1.0", "GHSA-second-cve");

    List<ScaReachabilityDependencyRegistry.DependencySnapshot> snapshots =
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();
    assertEquals(1, snapshots.size(), "only the updated dep must be pending");
    assertEquals(2, snapshots.get(0).cves.size(), "both CVEs must be present");
  }

  @Test
  void recordHit_atCap_newKeysRejectedButExistingKeyStillUpdated() {
    int cap = Config.get().getAppSecScaMaxTrackedDependencies();

    for (int i = 0; i < cap; i++) {
      ScaReachabilityDependencyRegistry.INSTANCE.registerCve("art" + i, "1.0", "GHSA-" + i);
    }
    ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();

    ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
        "art-over-cap", "1.0", "GHSA-over", "com.myapp.Ctrl", "handle", 42);
    assertTrue(
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies().isEmpty(),
        "over-cap hit for a new dependency must be rejected");

    ScaReachabilityDependencyRegistry.INSTANCE.recordHit(
        "art0", "1.0", "GHSA-0", "com.myapp.Ctrl", "handle", 42);
    List<ScaReachabilityDependencyRegistry.DependencySnapshot> snapshots =
        ScaReachabilityDependencyRegistry.INSTANCE.drainPendingDependencies();
    assertEquals(1, snapshots.size(), "existing dependency can still be updated at cap");
    assertEquals("art0", snapshots.get(0).artifact);
    assertNotNull(snapshots.get(0).cves.get(0).hit);
  }

  @Test
  void resetForTesting_clearsPeriodicWorkCallback() {
    ScaReachabilityDependencyRegistry.INSTANCE.setPeriodicWorkCallback(() -> {});
    assertNotNull(ScaReachabilityDependencyRegistry.INSTANCE.getPeriodicWorkCallback());

    ScaReachabilityDependencyRegistry.INSTANCE.resetForTesting();

    assertNull(
        ScaReachabilityDependencyRegistry.INSTANCE.getPeriodicWorkCallback(),
        "resetForTesting must clear periodicWorkCallback");
  }
}
