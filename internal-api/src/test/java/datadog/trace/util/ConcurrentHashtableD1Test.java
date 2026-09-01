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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConcurrentHashtableD1Test {

  @Test
  void getReturnsMappedEntry() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 8);
    StringEntry e = table.tryGetOrCreateOrNull("hello", k -> new StringEntry(k, 42));
    assertSame(e, table.get("hello"));
    assertNull(table.get("world"));
  }

  @Test
  void getOrCreateOnMissBuildsEntry() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 8);
    int[] createCount = {0};
    StringEntry created =
        table.tryGetOrCreateOrNull(
            "a",
            k -> {
              createCount[0]++;
              return new StringEntry(k, 1);
            });
    assertNotNull(created);
    assertEquals(1, table.size());
    assertEquals(1, createCount[0]);
    assertSame(created, table.get("a"));
  }

  @Test
  void getOrCreateOnHitSkipsCreator() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 8);
    StringEntry seeded = table.tryGetOrCreateOrNull("a", k -> new StringEntry(k, 100));
    int[] createCount = {0};
    StringEntry got =
        table.tryGetOrCreateOrNull(
            "a",
            k -> {
              createCount[0]++;
              return new StringEntry(k, 999);
            });
    assertSame(seeded, got);
    assertEquals(1, table.size());
    assertEquals(0, createCount[0]);
  }

  @Test
  void nullKeyIsSupported() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 8);
    StringEntry e = table.tryGetOrCreateOrNull(null, k -> new StringEntry(k, 0));
    assertNotNull(e);
    assertSame(e, table.get(null));
  }

  @Test
  void forEachVisitsAllEntries() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 8);
    table.tryGetOrCreateOrNull("a", k -> new StringEntry(k, 1));
    table.tryGetOrCreateOrNull("b", k -> new StringEntry(k, 2));
    table.tryGetOrCreateOrNull("c", k -> new StringEntry(k, 3));
    Set<String> seen = new HashSet<>();
    table.forEach(e -> seen.add(e.key));
    assertEquals(3, seen.size());
    assertTrue(seen.contains("a"));
    assertTrue(seen.contains("b"));
    assertTrue(seen.contains("c"));
  }

  @Test
  void forEachWithContextPassesContext() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 8);
    table.tryGetOrCreateOrNull("x", k -> new StringEntry(k, 10));
    table.tryGetOrCreateOrNull("y", k -> new StringEntry(k, 20));
    Set<String> seen = new HashSet<>();
    table.forEach(seen, (ctx, e) -> ctx.add(e.key));
    assertEquals(2, seen.size());
    assertTrue(seen.contains("x"));
    assertTrue(seen.contains("y"));
  }

  @Test
  void concurrentGetOrCreateProducesExactlyOneEntry() throws InterruptedException {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 8);
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
                    k -> {
                      createCount.incrementAndGet();
                      return new StringEntry(k, 1);
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
    // All three keys share hash 0, so they land in the same bucket regardless of table size.
    ConcurrentHashtable.D1<CollidingKey, CollidingEntry> table =
        ConcurrentHashtable.D1.createBounded(CollidingEntry.class, 8);
    CollidingKey a = new CollidingKey("a", 0);
    CollidingKey b = new CollidingKey("b", 0); // same bucket as a
    CollidingKey c = new CollidingKey("c", 0); // same bucket
    CollidingEntry ea = table.tryGetOrCreateOrNull(a, CollidingEntry::new);
    CollidingEntry eb = table.tryGetOrCreateOrNull(b, CollidingEntry::new);
    CollidingEntry ec = table.tryGetOrCreateOrNull(c, CollidingEntry::new);
    assertEquals(3, table.size());
    assertSame(ea, table.get(a));
    assertSame(eb, table.get(b));
    assertSame(ec, table.get(c));
    assertNull(table.get(new CollidingKey("d", 0))); // same bucket, different label → miss
  }

  @Test
  void concurrentDistinctKeyInsertionsAreAllRetained() throws InterruptedException {
    int threads = 16;
    String[] keys = new String[threads];
    for (int i = 0; i < threads; i++) {
      keys[i] = "key-" + i;
    }
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, threads * 2);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);

    Thread[] workers = new Thread[threads];
    for (int i = 0; i < threads; i++) {
      final String key = keys[i];
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
                table.tryGetOrCreateOrNull(key, k -> new StringEntry(k, 1));
              });
      workers[i].start();
    }
    ready.await();
    go.countDown();
    for (Thread w : workers) {
      w.join();
    }

    assertEquals(threads, table.size());
    for (String key : keys) {
      assertNotNull(table.get(key));
    }
  }

  @Test
  void removeReturnsEntryAndShrinks() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 8);
    StringEntry a = table.tryGetOrCreateOrNull("a", k -> new StringEntry(k, 1));
    table.tryGetOrCreateOrNull("b", k -> new StringEntry(k, 2));
    assertSame(a, table.remove("a"));
    assertEquals(1, table.size());
    assertNull(table.get("a"));
    assertNotNull(table.get("b"));
  }

  @Test
  void removeAbsentKeyReturnsNull() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 8);
    table.tryGetOrCreateOrNull("a", k -> new StringEntry(k, 1));
    assertNull(table.remove("missing"));
    assertEquals(1, table.size());
  }

  @Test
  void removeHeadMiddleAndTailOfSameBucketChain() {
    // All three keys share hash 0, so a, b, c land in the same bucket and form one chain.
    ConcurrentHashtable.D1<CollidingKey, CollidingEntry> table =
        ConcurrentHashtable.D1.createBounded(CollidingEntry.class, 8);
    CollidingKey a = new CollidingKey("a", 0);
    CollidingKey b = new CollidingKey("b", 0);
    CollidingKey c = new CollidingKey("c", 0);
    table.tryGetOrCreateOrNull(a, CollidingEntry::new);
    table.tryGetOrCreateOrNull(b, CollidingEntry::new);
    table.tryGetOrCreateOrNull(c, CollidingEntry::new);

    // Remove a middle element; the other two stay reachable.
    assertNotNull(table.remove(b));
    assertNull(table.get(b));
    assertNotNull(table.get(a));
    assertNotNull(table.get(c));
    assertEquals(2, table.size());

    // Drain the rest.
    assertNotNull(table.remove(c));
    assertNotNull(table.remove(a));
    assertEquals(0, table.size());
    assertNull(table.get(a));
  }

  @Test
  void removeIfRemovesMatchingEntries() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 16);
    for (int i = 0; i < 10; i++) {
      final int v = i;
      table.tryGetOrCreateOrNull("k" + i, k -> new StringEntry(k, v));
    }
    boolean removed = table.removeIf(e -> e.value % 2 == 0); // removes values 0,2,4,6,8
    assertTrue(removed);
    assertEquals(5, table.size());
    Set<String> seen = new HashSet<>();
    table.forEach(e -> seen.add(e.key));
    assertEquals(5, seen.size());
    for (String key : seen) {
      assertNotNull(table.get(key));
    }
  }

  @Test
  void removeIfReturnsFalseWhenNothingMatches() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 8);
    table.tryGetOrCreateOrNull("a", k -> new StringEntry(k, 1));
    assertFalse(table.removeIf(e -> false));
    assertEquals(1, table.size());
  }

  @Test
  void clearEmptiesTableAndLeavesItUsable() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 8);
    table.tryGetOrCreateOrNull("a", k -> new StringEntry(k, 1));
    table.tryGetOrCreateOrNull("b", k -> new StringEntry(k, 2));
    table.clear();
    assertEquals(0, table.size());
    assertNull(table.get("a"));
    assertNull(table.get("b"));
    StringEntry c = table.tryGetOrCreateOrNull("c", k -> new StringEntry(k, 3));
    assertSame(c, table.get("c"));
    assertEquals(1, table.size());
  }

  @Test
  void drainRemovesEveryEntryAndFeedsSink() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 8);
    table.tryGetOrCreateOrNull("a", k -> new StringEntry(k, 1));
    table.tryGetOrCreateOrNull("b", k -> new StringEntry(k, 2));
    table.tryGetOrCreateOrNull("c", k -> new StringEntry(k, 3));

    Set<String> drained = new HashSet<>();
    int[] sum = {0};
    table.drain(
        e -> {
          drained.add(e.key);
          sum[0] += e.value;
        });

    assertEquals(new HashSet<>(Arrays.asList("a", "b", "c")), drained);
    assertEquals(6, sum[0]);
    assertEquals(0, table.size());
    assertNull(table.get("a"));
    // table remains usable after drain
    StringEntry d = table.tryGetOrCreateOrNull("d", k -> new StringEntry(k, 4));
    assertSame(d, table.get("d"));
    assertEquals(1, table.size());
  }

  @Test
  void drainWithContextFeedsSink() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 8);
    table.tryGetOrCreateOrNull("a", k -> new StringEntry(k, 1));
    table.tryGetOrCreateOrNull("b", k -> new StringEntry(k, 2));

    Set<String> drained = new HashSet<>();
    table.drain(drained, (ctx, e) -> ctx.add(e.key));

    assertEquals(new HashSet<>(Arrays.asList("a", "b")), drained);
    assertEquals(0, table.size());
  }

  @Test
  void drainOnEmptyTableInvokesSinkZeroTimes() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 8);
    int[] count = {0};
    table.drain(e -> count[0]++);
    assertEquals(0, count[0]);
    assertEquals(0, table.size());
  }

  /**
   * Exercises the volatile-{@code next} removal contract: while one key is repeatedly removed and
   * re-added in a shared collision chain, the other keys in that chain must remain continuously
   * visible to a concurrent lock-free reader.
   */
  @Test
  void concurrentReadsStaySafeWhileOneChainMemberChurns() throws InterruptedException {
    // All keys share hash 0, putting every key in one bucket so removal splices a chain the
    // reader is walking.
    ConcurrentHashtable.D1<CollidingKey, CollidingEntry> table =
        ConcurrentHashtable.D1.createBounded(CollidingEntry.class, 16);
    int n = 8;
    CollidingKey[] keys = new CollidingKey[n];
    for (int i = 0; i < n; i++) {
      keys[i] = new CollidingKey("k" + i, 0);
      table.tryGetOrCreateOrNull(keys[i], CollidingEntry::new);
    }
    CollidingKey churn = keys[0]; // keys[1..] are stable and must never vanish

    AtomicBoolean stop = new AtomicBoolean(false);
    AtomicInteger missed = new AtomicInteger();
    Thread reader =
        new Thread(
            () -> {
              while (!stop.get()) {
                for (int i = 1; i < n; i++) {
                  if (table.get(keys[i]) == null) {
                    missed.incrementAndGet();
                  }
                }
              }
            });
    reader.start();
    for (int r = 0; r < 100_000; r++) {
      table.remove(churn);
      table.tryGetOrCreateOrNull(churn, CollidingEntry::new);
    }
    stop.set(true);
    reader.join();

    assertEquals(0, missed.get(), "stable chain members must never be unreachable during removal");
  }

  @Test
  void tryGetOrCreateOrEvictInsertsWithoutEvictingWhenUnderCapacity() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 8);
    Maybe<StringEntry> created =
        table.tryGetOrCreateOrEvict("a", k -> new StringEntry(k, 1), e -> true);
    assertTrue(created.isPresent());
    assertEquals(1, table.size());
    assertSame(created.getOrNull(), table.get("a"));
  }

  @Test
  void tryGetOrCreateOrEvictReturnsExistingEntryOnHitWithoutEvicting() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 1);
    StringEntry a = table.tryGetOrCreateOrNull("a", k -> new StringEntry(k, 1));
    Maybe<StringEntry> got =
        table.tryGetOrCreateOrEvict(
            "a",
            k -> {
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
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 1);
    table.tryGetOrCreateOrNull("old", k -> new StringEntry(k, 1));
    assertTrue(table.isFull());

    Maybe<StringEntry> created =
        table.tryGetOrCreateOrEvict("new", k -> new StringEntry(k, 2), e -> true);
    assertTrue(created.isPresent());
    assertEquals("new", created.getOrNull().key);
    assertEquals(1, table.size());
    assertNull(table.get("old"));
    assertSame(created.getOrNull(), table.get("new"));
  }

  @Test
  void tryGetOrCreateOrEvictOrNullRefusesWhenFullAndNothingEvictable() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 1);
    table.tryGetOrCreateOrNull("old", k -> new StringEntry(k, 1));

    StringEntry result =
        table.tryGetOrCreateOrEvictOrNull("new", k -> new StringEntry(k, 2), e -> false);
    assertNull(result);
    assertEquals(1, table.size());
    assertNotNull(table.get("old"));
    assertNull(table.get("new"));
  }

  @Test
  void tryGetOrCreateOrEvictOrNullEvictionRunsBeforeThrowingCreator() {
    ConcurrentHashtable.D1<String, StringEntry> table =
        ConcurrentHashtable.D1.createBounded(StringEntry.class, 1);
    table.tryGetOrCreateOrNull("old", k -> new StringEntry(k, 1));

    assertThrows(
        RuntimeException.class,
        () ->
            table.tryGetOrCreateOrEvictOrNull(
                "new",
                k -> {
                  throw new RuntimeException("boom");
                },
                e -> true));

    // Eviction already happened before the creator threw: the table is left one entry smaller,
    // not corrupted or double-booked.
    assertEquals(0, table.size());
    assertNull(table.get("old"));
    assertNull(table.get("new"));
  }

  private static final class StringEntry extends ConcurrentHashtable.D1.Entry<String> {
    final int value;

    StringEntry(String key, int value) {
      super(key);
      this.value = value;
    }
  }

  /** Key with a fixed hashCode to force deterministic bucket placement. */
  private static final class CollidingKey {
    final String label;
    final int fixedHash;

    CollidingKey(String label, int fixedHash) {
      this.label = label;
      this.fixedHash = fixedHash;
    }

    @Override
    public int hashCode() {
      return fixedHash;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof CollidingKey)) {
        return false;
      }
      CollidingKey that = (CollidingKey) o;
      return fixedHash == that.fixedHash && label.equals(that.label);
    }
  }

  private static final class CollidingEntry extends ConcurrentHashtable.D1.Entry<CollidingKey> {
    CollidingEntry(CollidingKey key) {
      super(key);
    }
  }
}
