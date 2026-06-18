package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConcurrentHashtableD1Test {

  @Test
  void getReturnsMappedEntry() {
    ConcurrentHashtable.D1<String, StringEntry> table = new ConcurrentHashtable.D1<>(8);
    StringEntry e = table.getOrCreate("hello", k -> new StringEntry(k, 42));
    assertSame(e, table.get("hello"));
    assertNull(table.get("world"));
  }

  @Test
  void getOrCreateOnMissBuildsEntry() {
    ConcurrentHashtable.D1<String, StringEntry> table = new ConcurrentHashtable.D1<>(8);
    int[] createCount = {0};
    StringEntry created =
        table.getOrCreate(
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
    ConcurrentHashtable.D1<String, StringEntry> table = new ConcurrentHashtable.D1<>(8);
    StringEntry seeded = table.getOrCreate("a", k -> new StringEntry(k, 100));
    int[] createCount = {0};
    StringEntry got =
        table.getOrCreate(
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
    ConcurrentHashtable.D1<String, StringEntry> table = new ConcurrentHashtable.D1<>(8);
    StringEntry e = table.getOrCreate(null, k -> new StringEntry(k, 0));
    assertNotNull(e);
    assertSame(e, table.get(null));
  }

  @Test
  void forEachVisitsAllEntries() {
    ConcurrentHashtable.D1<String, StringEntry> table = new ConcurrentHashtable.D1<>(8);
    table.getOrCreate("a", k -> new StringEntry(k, 1));
    table.getOrCreate("b", k -> new StringEntry(k, 2));
    table.getOrCreate("c", k -> new StringEntry(k, 3));
    Set<String> seen = new HashSet<>();
    table.forEach(e -> seen.add(e.key));
    assertEquals(3, seen.size());
    assertTrue(seen.contains("a"));
    assertTrue(seen.contains("b"));
    assertTrue(seen.contains("c"));
  }

  @Test
  void forEachWithContextPassesContext() {
    ConcurrentHashtable.D1<String, StringEntry> table = new ConcurrentHashtable.D1<>(8);
    table.getOrCreate("x", k -> new StringEntry(k, 10));
    table.getOrCreate("y", k -> new StringEntry(k, 20));
    Set<String> seen = new HashSet<>();
    table.forEach(seen, (ctx, e) -> ctx.add(e.key));
    assertEquals(2, seen.size());
    assertTrue(seen.contains("x"));
    assertTrue(seen.contains("y"));
  }

  @Test
  void concurrentGetOrCreateProducesExactlyOneEntry() throws InterruptedException {
    ConcurrentHashtable.D1<String, StringEntry> table = new ConcurrentHashtable.D1<>(8);
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
    // 2 buckets: keyHash & 1 determines the slot. Hashes 0 and 2 both land in bucket 0.
    ConcurrentHashtable.D1<CollidingKey, CollidingEntry> table = new ConcurrentHashtable.D1<>(2);
    CollidingKey a = new CollidingKey("a", 0);
    CollidingKey b = new CollidingKey("b", 0); // same bucket as a
    CollidingKey c = new CollidingKey("c", 2); // 2 & 1 == 0, same bucket
    CollidingEntry ea = table.getOrCreate(a, CollidingEntry::new);
    CollidingEntry eb = table.getOrCreate(b, CollidingEntry::new);
    CollidingEntry ec = table.getOrCreate(c, CollidingEntry::new);
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
        new ConcurrentHashtable.D1<>(threads * 2);
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
                table.getOrCreate(key, k -> new StringEntry(k, 1));
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

  // Reuses Hashtable.D1.Entry — ConcurrentHashtable.D1 accepts any D1.Entry subclass.
  private static final class StringEntry extends Hashtable.D1.Entry<String> {
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

  private static final class CollidingEntry extends Hashtable.D1.Entry<CollidingKey> {
    CollidingEntry(CollidingKey key) {
      super(key);
    }
  }
}
