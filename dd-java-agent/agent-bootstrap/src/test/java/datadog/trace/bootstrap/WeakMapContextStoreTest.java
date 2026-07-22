package datadog.trace.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the {@link WeakMapContextStore} fall-back used when bytecode field injection
 * is unavailable — e.g. under JDK 25 AOT class linking, where carrier classes come pre-linked from
 * the cache and the context-store field cannot be added (<a
 * href="https://github.com/DataDog/dd-trace-java/issues/10479">issue #10479</a>).
 *
 * <p>The store used to silently discard new entries past a 50k cap measured with a stale
 * approximate size, dropping live trace context past ~50k context writes/sec. It must retain every
 * live carrier's context and reclaim entries once carriers are collected.
 */
class WeakMapContextStoreTest {

  // The former production cap whose overflow used to be dropped silently.
  private static final int FORMER_CAP = 50_000;

  @Test
  void retainsLiveContextBeyondFormerCap() {
    WeakMapContextStore<Object, Object> store = new WeakMapContextStore<>();

    List<Object> live = new ArrayList<>();
    for (int i = 0; i < FORMER_CAP + 1; i++) {
      Object carrier = new Object();
      live.add(carrier);
      store.put(carrier, "ctx-" + i);
    }

    assertEquals(FORMER_CAP + 1, store.size());
    assertNotNull(
        store.get(live.get(FORMER_CAP)),
        "fall-back store must not drop live context past the former cap (issue #10479)");
  }

  @Test
  void retainsLiveContextAfterHighChurnOfDeadCarriers() throws InterruptedException {
    WeakMapContextStore<Object, Object> store = new WeakMapContextStore<>();

    // Completed tasks: their carriers become garbage right after the context write. This is the
    // realistic #10479 trigger — sustained churn, few carriers actually live at any moment.
    WeakReference<Object> probe = null;
    for (int i = 0; i < FORMER_CAP; i++) {
      Object carrier = new Object();
      if (i == 0) {
        probe = new WeakReference<>(carrier);
      }
      store.put(carrier, "dead-" + i);
    }

    Assumptions.assumeTrue(awaitCollected(probe), "GC did not reclaim the dead carriers in time");

    Object liveCarrier = new Object();
    store.put(liveCarrier, "live-ctx");

    assertNotNull(
        store.get(liveCarrier),
        "fall-back store must not drop live context because of dead entries (issue #10479)");

    // Reference enqueueing is asynchronous, so poll until the dead entries drain away.
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (store.size() > 1 && System.nanoTime() < deadline) {
      System.gc();
      Thread.sleep(50);
    }
    assertEquals(1, store.size(), "collected carriers must be expunged, not counted");
  }

  @Test
  void removesEntryOnceCarrierIsCollected() throws InterruptedException {
    WeakMapContextStore<Object, Object> store = new WeakMapContextStore<>();

    Object carrier = new Object();
    WeakReference<Object> probe = new WeakReference<>(carrier);
    store.put(carrier, "ctx");
    assertEquals(1, store.size());

    carrier = null;
    Assumptions.assumeTrue(awaitCollected(probe), "GC did not reclaim the carrier in time");

    assertEquals(0, store.size(), "entry must be reclaimed with its carrier");
  }

  @Test
  void basicContextStoreContract() {
    WeakMapContextStore<Object, Object> store = new WeakMapContextStore<>();
    Object carrier = new Object();

    assertNull(store.get(carrier));
    assertSame("first", store.putIfAbsent(carrier, "first"));
    assertSame("first", store.putIfAbsent(carrier, "second"));
    assertSame("first", store.computeIfAbsent(carrier, k -> "third"));
    assertSame("first", store.get(carrier));

    store.put(carrier, "replaced");
    assertSame("replaced", store.get(carrier));

    assertSame("replaced", store.remove(carrier));
    assertNull(store.get(carrier));
    assertEquals(0, store.size());
  }

  @Test
  void factoryMayReenterTheStore() {
    // Context factories run arbitrary instrumentation code, which may touch the same store.
    // ConcurrentHashMap.computeIfAbsent forbids that from its mapping function, so the store
    // must not run the factory under the map's own locks.
    WeakMapContextStore<Object, Object> store = new WeakMapContextStore<>();
    Object carrier = new Object();
    Object other = new Object();

    Object context =
        store.computeIfAbsent(
            carrier,
            k -> {
              store.put(other, "nested-ctx");
              return "ctx";
            });

    assertSame("ctx", context);
    assertSame("ctx", store.get(carrier));
    assertSame("nested-ctx", store.get(other));
  }

  private static boolean awaitCollected(WeakReference<?> ref) throws InterruptedException {
    long deadline = System.nanoTime() + 10_000_000_000L;
    while (System.nanoTime() < deadline) {
      if (ref.get() == null) {
        return true;
      }
      System.gc();
      Thread.sleep(50);
    }
    return ref.get() == null;
  }
}
