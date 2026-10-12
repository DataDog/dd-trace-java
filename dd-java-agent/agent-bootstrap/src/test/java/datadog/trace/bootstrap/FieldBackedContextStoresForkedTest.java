package datadog.trace.bootstrap;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/** Forked to isolate the static context-store registry from other tests. */
class FieldBackedContextStoresForkedTest {

  private static final int THREAD_COUNT = 8;

  // enough stores to go past the pre-allocated stores, grow the array, and keep threads contending
  private static final int STORE_COUNT = FieldBackedContextStores.FAST_STORE_ID_LIMIT + 2000;

  @Test
  void sameNameReturnsSameStore() {
    int storeId = FieldBackedContextStores.getContextStoreId("sameKey", "sameContext");
    assertEquals(storeId, FieldBackedContextStores.getContextStoreId("sameKey", "sameContext"));
    assertNotEquals(storeId, FieldBackedContextStores.getContextStoreId("sameKey", "otherContext"));
    assertNotEquals(storeId, FieldBackedContextStores.getContextStoreId("otherKey", "sameContext"));
    assertEquals(storeId, FieldBackedContextStores.getContextStore(storeId).storeId);
  }

  @Test
  void concurrentAllocationAssignsOneContiguousIdPerName() throws Exception {
    List<String> keys = new ArrayList<>();
    for (int i = 0; i < STORE_COUNT; i++) {
      keys.add("concurrentKey" + i);
    }

    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
    try {
      List<Future<int[]>> results = new ArrayList<>();
      for (int t = 0; t < THREAD_COUNT; t++) {
        // every thread requests the same names in the same order to maximise contention
        results.add(
            executor.submit(
                () -> {
                  start.await();
                  int[] storeIds = new int[STORE_COUNT];
                  for (int i = 0; i < STORE_COUNT; i++) {
                    int storeId =
                        FieldBackedContextStores.getContextStoreId(keys.get(i), "context");
                    // store must be visible as soon as its id is returned
                    FieldBackedContextStore store =
                        FieldBackedContextStores.getContextStore(storeId);
                    assertNotNull(store, "Missing store for id " + storeId);
                    assertEquals(storeId, store.storeId);
                    storeIds[i] = storeId;
                  }
                  return storeIds;
                }));
      }
      start.countDown();

      int[] expectedIds = results.get(0).get(10, SECONDS);
      for (Future<int[]> result : results) {
        int[] storeIds = result.get(10, SECONDS);
        for (int i = 0; i < STORE_COUNT; i++) {
          assertEquals(expectedIds[i], storeIds[i], "Threads disagree on id for " + keys.get(i));
        }
      }

      TreeSet<Integer> distinctIds = new TreeSet<>();
      for (int storeId : expectedIds) {
        assertTrue(distinctIds.add(storeId), "Store id " + storeId + " allocated twice");
        FieldBackedContextStore store = FieldBackedContextStores.getContextStore(storeId);
        assertNotNull(store, "Missing store for id " + storeId);
        assertEquals(storeId, store.storeId);
      }
      assertEquals(
          STORE_COUNT - 1, distinctIds.last() - distinctIds.first(), "Store ids have gaps");
    } finally {
      executor.shutdownNow();
    }
  }
}
