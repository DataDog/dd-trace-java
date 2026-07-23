package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConcurrentHashtableD2Test {

  @Test
  void pairKeysParticipateInIdentity() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table = new ConcurrentHashtable.D2<>(8);
    PairEntry ab = table.getOrCreate("a", 1, PairEntry::new);
    PairEntry ac = table.getOrCreate("a", 2, PairEntry::new);
    PairEntry bb = table.getOrCreate("b", 1, PairEntry::new);
    assertEquals(3, table.size());
    assertSame(ab, table.get("a", 1));
    assertSame(ac, table.get("a", 2));
    assertSame(bb, table.get("b", 1));
    assertNull(table.get("a", 3));
  }

  @Test
  void getOrCreateOnMissBuildsEntryViaCreator() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table = new ConcurrentHashtable.D2<>(8);
    int[] createCount = {0};
    PairEntry created =
        table.getOrCreate(
            "a",
            1,
            (k1, k2) -> {
              createCount[0]++;
              return new PairEntry(k1, k2);
            });
    assertNotNull(created);
    assertEquals("a", created.key1);
    assertEquals(Integer.valueOf(1), created.key2);
    assertEquals(1, table.size());
    assertEquals(1, createCount[0]);
    assertSame(created, table.get("a", 1));
  }

  @Test
  void getOrCreateOnHitSkipsCreator() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table = new ConcurrentHashtable.D2<>(8);
    PairEntry seeded = table.getOrCreate("a", 1, PairEntry::new);
    int[] createCount = {0};
    PairEntry got =
        table.getOrCreate(
            "a",
            1,
            (k1, k2) -> {
              createCount[0]++;
              return new PairEntry(k1, k2);
            });
    assertSame(seeded, got);
    assertEquals(1, table.size());
    assertEquals(0, createCount[0]);
  }

  @Test
  void forEachVisitsBothPairs() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table = new ConcurrentHashtable.D2<>(8);
    table.getOrCreate("a", 1, PairEntry::new);
    table.getOrCreate("b", 2, PairEntry::new);
    Set<String> seen = new HashSet<>();
    table.forEach(e -> seen.add(e.key1 + ":" + e.key2));
    assertEquals(2, seen.size());
    assertTrue(seen.contains("a:1"));
    assertTrue(seen.contains("b:2"));
  }

  @Test
  void forEachWithContextPassesContextToConsumer() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table = new ConcurrentHashtable.D2<>(8);
    table.getOrCreate("a", 1, PairEntry::new);
    table.getOrCreate("b", 2, PairEntry::new);
    Set<String> seen = new HashSet<>();
    table.forEach(seen, (ctx, e) -> ctx.add(e.key1 + ":" + e.key2));
    assertEquals(2, seen.size());
    assertTrue(seen.contains("a:1"));
    assertTrue(seen.contains("b:2"));
  }

  @Test
  void concurrentGetOrCreateProducesExactlyOneEntry() throws InterruptedException {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table = new ConcurrentHashtable.D2<>(8);
    int threads = 16;
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger createCount = new AtomicInteger();

    Thread[] workers = new Thread[threads];
    for (int i = 0; i < threads; i++) {
      workers[i] =
          new Thread(
              () -> {
                ready.countDown();
                try {
                  go.await();
                } catch (InterruptedException ex) {
                  Thread.currentThread().interrupt();
                  return;
                }
                table.getOrCreate(
                    "shared",
                    42,
                    (k1, k2) -> {
                      createCount.incrementAndGet();
                      return new PairEntry(k1, k2);
                    });
              });
      workers[i].start();
    }
    ready.await();
    go.countDown();
    for (Thread w : workers) {
      w.join();
    }

    assertEquals(1, table.size());
    assertEquals(1, createCount.get());
  }

  @Test
  void chainedEntriesInSameBucketAreAllReachable() {
    // 2 buckets: 4 entries guarantees at least 2 share a bucket by pigeonhole.
    ConcurrentHashtable.D2<String, Integer, PairEntry> table = new ConcurrentHashtable.D2<>(2);
    PairEntry e1 = table.getOrCreate("a", 1, PairEntry::new);
    PairEntry e2 = table.getOrCreate("a", 2, PairEntry::new);
    PairEntry e3 = table.getOrCreate("b", 1, PairEntry::new);
    PairEntry e4 = table.getOrCreate("b", 2, PairEntry::new);
    assertEquals(4, table.size());
    assertSame(e1, table.get("a", 1));
    assertSame(e2, table.get("a", 2));
    assertSame(e3, table.get("b", 1));
    assertSame(e4, table.get("b", 2));
    assertNull(table.get("a", 3));
  }

  @Test
  void concurrentDistinctKeyInsertionsAreAllRetained() throws InterruptedException {
    int threads = 16;
    String[] k1s = new String[threads];
    Integer[] k2s = new Integer[threads];
    for (int i = 0; i < threads; i++) {
      k1s[i] = "key-" + i;
      k2s[i] = i;
    }
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        new ConcurrentHashtable.D2<>(threads * 2);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);

    Thread[] workers = new Thread[threads];
    for (int i = 0; i < threads; i++) {
      final String k1 = k1s[i];
      final Integer k2 = k2s[i];
      workers[i] =
          new Thread(
              () -> {
                ready.countDown();
                try {
                  go.await();
                } catch (InterruptedException ex) {
                  Thread.currentThread().interrupt();
                  return;
                }
                table.getOrCreate(k1, k2, PairEntry::new);
              });
      workers[i].start();
    }
    ready.await();
    go.countDown();
    for (Thread w : workers) {
      w.join();
    }

    assertEquals(threads, table.size());
    for (int i = 0; i < threads; i++) {
      assertNotNull(table.get(k1s[i], k2s[i]));
    }
  }

  @Test
  void removeReturnsEntryAndShrinks() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table = new ConcurrentHashtable.D2<>(8);
    PairEntry ab = table.getOrCreate("a", 1, PairEntry::new);
    table.getOrCreate("a", 2, PairEntry::new);
    assertSame(ab, table.remove("a", 1));
    assertEquals(1, table.size());
    assertNull(table.get("a", 1));
    assertNotNull(table.get("a", 2));
  }

  @Test
  void removeAbsentKeyReturnsNull() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table = new ConcurrentHashtable.D2<>(8);
    table.getOrCreate("a", 1, PairEntry::new);
    assertNull(table.remove("a", 99));
    assertNull(table.remove("z", 1));
    assertEquals(1, table.size());
  }

  @Test
  void removeMiddleOfSameBucketChainKeepsOthersReachable() {
    // Capacity 1 forces every pair into a single bucket chain.
    ConcurrentHashtable.D2<String, Integer, PairEntry> table = new ConcurrentHashtable.D2<>(1);
    table.getOrCreate("a", 1, PairEntry::new);
    PairEntry mid = table.getOrCreate("a", 2, PairEntry::new);
    table.getOrCreate("a", 3, PairEntry::new);

    assertSame(mid, table.remove("a", 2));
    assertNull(table.get("a", 2));
    assertNotNull(table.get("a", 1));
    assertNotNull(table.get("a", 3));
    assertEquals(2, table.size());
  }

  @Test
  void removeIfRemovesMatchingEntries() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table = new ConcurrentHashtable.D2<>(16);
    for (int i = 0; i < 10; i++) {
      table.getOrCreate("k", i, PairEntry::new);
    }
    boolean removed = table.removeIf(e -> e.key2 % 2 == 0); // removes key2 0,2,4,6,8
    assertTrue(removed);
    assertEquals(5, table.size());
    Set<String> seen = new HashSet<>();
    table.forEach(e -> seen.add(e.key1 + ":" + e.key2));
    assertEquals(5, seen.size());
  }

  @Test
  void removeIfReturnsFalseWhenNothingMatches() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table = new ConcurrentHashtable.D2<>(8);
    table.getOrCreate("a", 1, PairEntry::new);
    assertFalse(table.removeIf(e -> false));
    assertEquals(1, table.size());
  }

  @Test
  void clearEmptiesTableAndLeavesItUsable() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table = new ConcurrentHashtable.D2<>(8);
    table.getOrCreate("a", 1, PairEntry::new);
    table.getOrCreate("b", 2, PairEntry::new);
    table.clear();
    assertEquals(0, table.size());
    assertNull(table.get("a", 1));
    PairEntry c = table.getOrCreate("c", 3, PairEntry::new);
    assertSame(c, table.get("c", 3));
    assertEquals(1, table.size());
  }

  @Test
  void drainRemovesEveryEntryAndFeedsSink() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table = new ConcurrentHashtable.D2<>(8);
    table.getOrCreate("a", 1, PairEntry::new);
    table.getOrCreate("a", 2, PairEntry::new);
    table.getOrCreate("b", 1, PairEntry::new);

    Set<String> drained = new HashSet<>();
    table.drain(e -> drained.add(e.key1 + ":" + e.key2));

    assertEquals(new HashSet<>(Arrays.asList("a:1", "a:2", "b:1")), drained);
    assertEquals(0, table.size());
    assertNull(table.get("a", 1));
    PairEntry c = table.getOrCreate("c", 3, PairEntry::new);
    assertSame(c, table.get("c", 3));
    assertEquals(1, table.size());
  }

  @Test
  void drainWithContextFeedsSink() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table = new ConcurrentHashtable.D2<>(8);
    table.getOrCreate("a", 1, PairEntry::new);
    table.getOrCreate("b", 2, PairEntry::new);

    Set<String> drained = new HashSet<>();
    table.drain(drained, (ctx, e) -> ctx.add(e.key1 + ":" + e.key2));

    assertEquals(new HashSet<>(Arrays.asList("a:1", "b:2")), drained);
    assertEquals(0, table.size());
  }

  private static final class PairEntry extends ConcurrentHashtable.D2.Entry<String, Integer> {
    PairEntry(String key1, Integer key2) {
      super(key1, key2);
    }
  }
}
