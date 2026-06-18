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

  // Reuses Hashtable.D1.Entry — ConcurrentHashtable.D1 accepts any D1.Entry subclass.
  private static final class StringEntry extends Hashtable.D1.Entry<String> {
    final int value;

    StringEntry(String key, int value) {
      super(key);
      this.value = value;
    }
  }
}
