package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 8);
    PairEntry ab = table.tryGetOrCreateOrNull("a", 1, PairEntry::new);
    PairEntry ac = table.tryGetOrCreateOrNull("a", 2, PairEntry::new);
    PairEntry bb = table.tryGetOrCreateOrNull("b", 1, PairEntry::new);
    assertEquals(3, table.size());
    assertSame(ab, table.get("a", 1));
    assertSame(ac, table.get("a", 2));
    assertSame(bb, table.get("b", 1));
    assertNull(table.get("a", 3));
  }

  @Test
  void getOrCreateOnMissBuildsEntryViaCreator() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 8);
    int[] createCount = {0};
    PairEntry created =
        table.tryGetOrCreateOrNull(
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
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 8);
    PairEntry seeded = table.tryGetOrCreateOrNull("a", 1, PairEntry::new);
    int[] createCount = {0};
    PairEntry got =
        table.tryGetOrCreateOrNull(
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
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 8);
    table.tryGetOrCreateOrNull("a", 1, PairEntry::new);
    table.tryGetOrCreateOrNull("b", 2, PairEntry::new);
    Set<String> seen = new HashSet<>();
    table.forEach(e -> seen.add(e.key1 + ":" + e.key2));
    assertEquals(2, seen.size());
    assertTrue(seen.contains("a:1"));
    assertTrue(seen.contains("b:2"));
  }

  @Test
  void forEachWithContextPassesContextToConsumer() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 8);
    table.tryGetOrCreateOrNull("a", 1, PairEntry::new);
    table.tryGetOrCreateOrNull("b", 2, PairEntry::new);
    Set<String> seen = new HashSet<>();
    table.forEach(seen, (ctx, e) -> ctx.add(e.key1 + ":" + e.key2));
    assertEquals(2, seen.size());
    assertTrue(seen.contains("a:1"));
    assertTrue(seen.contains("b:2"));
  }

  @Test
  void concurrentGetOrCreateProducesExactlyOneEntry() throws InterruptedException {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 8);
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
                table.tryGetOrCreateOrNull(
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
    // key2 = -31 * key1.hashCode() zeroes the combined hash, so all four land in bucket 0
    // regardless of table size.
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 8);
    PairEntry e1 = table.tryGetOrCreateOrNull("a", -31 * "a".hashCode(), PairEntry::new);
    PairEntry e2 = table.tryGetOrCreateOrNull("b", -31 * "b".hashCode(), PairEntry::new);
    PairEntry e3 = table.tryGetOrCreateOrNull("c", -31 * "c".hashCode(), PairEntry::new);
    PairEntry e4 = table.tryGetOrCreateOrNull("d", -31 * "d".hashCode(), PairEntry::new);
    assertEquals(4, table.size());
    assertSame(e1, table.get("a", -31 * "a".hashCode()));
    assertSame(e2, table.get("b", -31 * "b".hashCode()));
    assertSame(e3, table.get("c", -31 * "c".hashCode()));
    assertSame(e4, table.get("d", -31 * "d".hashCode()));
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
        ConcurrentHashtable.D2.createBounded(PairEntry.class, threads * 2);
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
                table.tryGetOrCreateOrNull(k1, k2, PairEntry::new);
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
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 8);
    PairEntry ab = table.tryGetOrCreateOrNull("a", 1, PairEntry::new);
    table.tryGetOrCreateOrNull("a", 2, PairEntry::new);
    assertSame(ab, table.remove("a", 1));
    assertEquals(1, table.size());
    assertNull(table.get("a", 1));
    assertNotNull(table.get("a", 2));
  }

  @Test
  void removeAbsentKeyReturnsNull() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 8);
    table.tryGetOrCreateOrNull("a", 1, PairEntry::new);
    assertNull(table.remove("a", 99));
    assertNull(table.remove("z", 1));
    assertEquals(1, table.size());
  }

  @Test
  void removeMiddleOfSameBucketChainKeepsOthersReachable() {
    // key2 = -31 * key1.hashCode() zeroes the combined hash, so all three land in one bucket
    // chain regardless of table size.
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 8);
    table.tryGetOrCreateOrNull("a", -31 * "a".hashCode(), PairEntry::new);
    PairEntry mid = table.tryGetOrCreateOrNull("b", -31 * "b".hashCode(), PairEntry::new);
    table.tryGetOrCreateOrNull("c", -31 * "c".hashCode(), PairEntry::new);

    assertSame(mid, table.remove("b", -31 * "b".hashCode()));
    assertNull(table.get("b", -31 * "b".hashCode()));
    assertNotNull(table.get("a", -31 * "a".hashCode()));
    assertNotNull(table.get("c", -31 * "c".hashCode()));
    assertEquals(2, table.size());
  }

  @Test
  void removeIfRemovesMatchingEntries() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 16);
    for (int i = 0; i < 10; i++) {
      table.tryGetOrCreateOrNull("k", i, PairEntry::new);
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
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 8);
    table.tryGetOrCreateOrNull("a", 1, PairEntry::new);
    assertFalse(table.removeIf(e -> false));
    assertEquals(1, table.size());
  }

  @Test
  void clearEmptiesTableAndLeavesItUsable() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 8);
    table.tryGetOrCreateOrNull("a", 1, PairEntry::new);
    table.tryGetOrCreateOrNull("b", 2, PairEntry::new);
    table.clear();
    assertEquals(0, table.size());
    assertNull(table.get("a", 1));
    PairEntry c = table.tryGetOrCreateOrNull("c", 3, PairEntry::new);
    assertSame(c, table.get("c", 3));
    assertEquals(1, table.size());
  }

  @Test
  void drainRemovesEveryEntryAndFeedsSink() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 8);
    table.tryGetOrCreateOrNull("a", 1, PairEntry::new);
    table.tryGetOrCreateOrNull("a", 2, PairEntry::new);
    table.tryGetOrCreateOrNull("b", 1, PairEntry::new);

    Set<String> drained = new HashSet<>();
    table.drain(e -> drained.add(e.key1 + ":" + e.key2));

    assertEquals(new HashSet<>(Arrays.asList("a:1", "a:2", "b:1")), drained);
    assertEquals(0, table.size());
    assertNull(table.get("a", 1));
    PairEntry c = table.tryGetOrCreateOrNull("c", 3, PairEntry::new);
    assertSame(c, table.get("c", 3));
    assertEquals(1, table.size());
  }

  @Test
  void drainWithContextFeedsSink() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 8);
    table.tryGetOrCreateOrNull("a", 1, PairEntry::new);
    table.tryGetOrCreateOrNull("b", 2, PairEntry::new);

    Set<String> drained = new HashSet<>();
    table.drain(drained, (ctx, e) -> ctx.add(e.key1 + ":" + e.key2));

    assertEquals(new HashSet<>(Arrays.asList("a:1", "b:2")), drained);
    assertEquals(0, table.size());
  }

  @Test
  void tryGetOrCreateOrEvictInsertsWithoutEvictingWhenUnderCapacity() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 8);
    Maybe<PairEntry> created = table.tryGetOrCreateOrEvict("a", 1, PairEntry::new, e -> true);
    assertTrue(created.isPresent());
    assertEquals(1, table.size());
    assertSame(created.getOrNull(), table.get("a", 1));
  }

  @Test
  void tryGetOrCreateOrEvictReturnsExistingEntryOnHitWithoutEvicting() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 1);
    PairEntry a = table.tryGetOrCreateOrNull("a", 1, PairEntry::new);
    Maybe<PairEntry> got =
        table.tryGetOrCreateOrEvict(
            "a",
            1,
            (k1, k2) -> {
              throw new AssertionError("creator must not run on a hit");
            },
            e -> {
              throw new AssertionError("evictable must not run on a hit");
            });
    assertSame(a, got.getOrNull());
    assertEquals(1, table.size());
  }

  @Test
  void tryGetOrCreateOrEvictEvictsWhenFullAndInsertsNewEntry() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 1);
    table.tryGetOrCreateOrNull("old", 1, PairEntry::new);
    assertTrue(table.isFull());

    Maybe<PairEntry> created = table.tryGetOrCreateOrEvict("new", 2, PairEntry::new, e -> true);
    assertTrue(created.isPresent());
    assertEquals("new", created.getOrNull().key1);
    assertEquals(1, table.size());
    assertNull(table.get("old", 1));
    assertSame(created.getOrNull(), table.get("new", 2));
  }

  @Test
  void tryGetOrCreateOrEvictOrNullRefusesWhenFullAndNothingEvictable() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 1);
    table.tryGetOrCreateOrNull("old", 1, PairEntry::new);

    PairEntry result = table.tryGetOrCreateOrEvictOrNull("new", 2, PairEntry::new, e -> false);
    assertNull(result);
    assertEquals(1, table.size());
    assertNotNull(table.get("old", 1));
    assertNull(table.get("new", 2));
  }

  @Test
  void tryGetOrCreateOrEvictOrNullEvictionRunsBeforeThrowingCreator() {
    ConcurrentHashtable.D2<String, Integer, PairEntry> table =
        ConcurrentHashtable.D2.createBounded(PairEntry.class, 1);
    table.tryGetOrCreateOrNull("old", 1, PairEntry::new);

    assertThrows(
        RuntimeException.class,
        () ->
            table.tryGetOrCreateOrEvictOrNull(
                "new",
                2,
                (k1, k2) -> {
                  throw new RuntimeException("boom");
                },
                e -> true));

    // Eviction already happened before the creator threw: the table is left one entry smaller,
    // not corrupted or double-booked.
    assertEquals(0, table.size());
    assertNull(table.get("old", 1));
    assertNull(table.get("new", 2));
  }

  private static final class PairEntry extends ConcurrentHashtable.D2.Entry<String, Integer> {
    PairEntry(String key1, Integer key2) {
      super(key1, key2);
    }
  }
}
