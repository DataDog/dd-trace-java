package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.junit.jupiter.api.Test;

/**
 * Exercises the {@code static} building blocks that {@link ConcurrentHashtable} exposes for the
 * caller-owned-array path — the custom-table API used when {@link ConcurrentHashtable.D1}/{@link
 * ConcurrentHashtable.D2}'s object-key constraints don't fit (primitive keys, higher arity, extra
 * per-entry fields). {@link IntTable} below is a minimal hand-written table with a primitive {@code
 * int} key, driving the same lock-free-read / locked-write recipe the class Javadoc documents.
 */
class ConcurrentHashtableStaticsTest {

  @Test
  void sizeForRoundsUpToPowerOfTwo() {
    assertEquals(1, ConcurrentHashtable.sizeFor(1));
    assertEquals(8, ConcurrentHashtable.sizeFor(5));
    assertEquals(8, ConcurrentHashtable.sizeFor(8));
    assertEquals(16, ConcurrentHashtable.sizeFor(9));
  }

  @Test
  void createFixedBucketsAllocatesPowerOfTwoSpine() {
    AtomicReferenceArray<IntEntry> buckets =
        ConcurrentHashtable.createFixedBuckets(IntEntry.class, 10);
    assertEquals(16, buckets.length());
    assertNull(buckets.get(0));
  }

  @Test
  void getWriteLockIsStableAndNonNull() {
    AtomicReferenceArray<IntEntry> buckets =
        ConcurrentHashtable.createFixedBuckets(IntEntry.class, 8);
    Object lock = ConcurrentHashtable.getWriteLock(buckets, 3L);
    assertNotNull(lock);
    assertSame(lock, ConcurrentHashtable.getWriteLock(buckets, 3L));
    // The keyHash form is defined as the index form under bucketIndex.
    assertSame(
        lock,
        ConcurrentHashtable.getWriteLockAt(buckets, ConcurrentHashtable.bucketIndex(buckets, 3L)));
  }

  /**
   * The three accessors name three scopes, but a single-lock table answers all of them with one
   * monitor. Asserting that pins today's granularity as a deliberate choice rather than an
   * accident: if it ever changes, this is the test that says so, and callers that asked for the
   * scope they actually mutate keep working.
   */
  @Test
  void allWriteLockScopesResolveToOneMonitorToday() {
    AtomicReferenceArray<IntEntry> buckets =
        ConcurrentHashtable.createFixedBuckets(IntEntry.class, 8);
    Object table = ConcurrentHashtable.getTableWriteLock(buckets);
    assertNotNull(table);
    for (int index = 0; index < buckets.length(); index++) {
      assertSame(table, ConcurrentHashtable.getWriteLockAt(buckets, index));
      assertSame(table, ConcurrentHashtable.getWriteLock(buckets, index));
    }
  }

  @Test
  void bucketIndexMasksToArrayLength() {
    AtomicReferenceArray<IntEntry> buckets =
        ConcurrentHashtable.createFixedBuckets(IntEntry.class, 8); // length 8, mask 7
    assertEquals(0, ConcurrentHashtable.bucketIndex(buckets, 8L));
    assertEquals(1, ConcurrentHashtable.bucketIndex(buckets, 9L));
    assertEquals(7, ConcurrentHashtable.bucketIndex(buckets, 7L));
  }

  @Test
  void insertGetAndRemoveViaStatics() {
    IntTable table = new IntTable(8);
    IntEntry a = table.getOrCreate(1, 100);
    IntEntry b = table.getOrCreate(2, 200);
    assertEquals(2, table.size.get());
    assertSame(a, table.get(1));
    assertSame(b, table.get(2));
    assertNull(table.get(3));

    // getOrCreate on a hit returns the existing entry, no new insert.
    assertSame(a, table.getOrCreate(1, 999));
    assertEquals(2, table.size.get());

    assertSame(a, table.remove(1));
    assertNull(table.get(1));
    assertNull(table.remove(1)); // already gone
    assertEquals(1, table.size.get());
  }

  @Test
  void insertHeadEntryForPlacesInBucketMaskedFromKeyHash() {
    AtomicReferenceArray<IntEntry> buckets =
        ConcurrentHashtable.createFixedBuckets(IntEntry.class, 8); // mask 7
    IntEntry e = new IntEntry(9, 1); // keyHash 9 → bucket 1
    synchronized (ConcurrentHashtable.getWriteLock(buckets, e.keyHash)) {
      ConcurrentHashtable.insertHeadEntryFor(buckets, e.keyHash, e);
    }
    assertSame(e, ConcurrentHashtable.bucketFor(buckets, 9L)); // masks keyHash to the bucket index
    assertSame(
        e, ConcurrentHashtable.bucketAt(buckets, 1)); // same slot, addressed directly by index
    assertNull(buckets.get(0));
  }

  @Test
  void unlinkRemovesHeadMiddleAndTailOfChain() {
    // Capacity 1 → mask 0 → every key lands in bucket 0, forming one chain.
    IntTable table = new IntTable(1);
    IntEntry a = table.getOrCreate(1, 1);
    IntEntry b = table.getOrCreate(2, 2);
    IntEntry c = table.getOrCreate(3, 3);
    assertEquals(3, table.size.get());

    // Remove the middle: head and tail stay reachable.
    assertSame(b, table.remove(2));
    assertNull(table.get(2));
    assertSame(a, table.get(1));
    assertSame(c, table.get(3));
    assertEquals(2, table.size.get());

    // Remove the head, then the last remaining.
    assertSame(c, table.remove(3));
    assertSame(a, table.remove(1));
    assertEquals(0, table.size.get());
    assertNull(table.get(1));
  }

  @Test
  void staticRemoveIfRemovesMatchingAndDecrementsSize() {
    IntTable table = new IntTable(16);
    for (int i = 0; i < 10; i++) {
      table.getOrCreate(i, i);
    }
    boolean removed =
        ConcurrentHashtable.removeIf(table.buckets, table.size, e -> e.value % 2 == 0);
    assertTrue(removed);
    assertEquals(5, table.size.get());
    for (int i = 0; i < 10; i++) {
      if (i % 2 == 0) {
        assertNull(table.get(i));
      } else {
        assertNotNull(table.get(i));
      }
    }
  }

  @Test
  void staticRemoveIfReturnsFalseWhenNothingMatches() {
    IntTable table = new IntTable(8);
    table.getOrCreate(1, 1);
    assertFalse(ConcurrentHashtable.removeIf(table.buckets, table.size, e -> false));
    assertEquals(1, table.size.get());
  }

  @Test
  void staticDrainRemovesEveryEntryAndFeedsSink() {
    IntTable table = new IntTable(8);
    table.getOrCreate(1, 10);
    table.getOrCreate(2, 20);
    table.getOrCreate(3, 30);

    Set<Integer> keys = new HashSet<>();
    int[] sum = {0};
    ConcurrentHashtable.drain(
        table.buckets,
        e -> {
          keys.add(e.key);
          sum[0] += e.value;
        });

    assertEquals(new HashSet<>(java.util.Arrays.asList(1, 2, 3)), keys);
    assertEquals(60, sum[0]);
    // drain does not touch size accounting on the static path — the caller resets it.
    for (int i = 1; i <= 3; i++) {
      assertNull(table.get(i));
    }
  }

  @Test
  void staticDrainWithContextFeedsSink() {
    IntTable table = new IntTable(8);
    table.getOrCreate(1, 10);
    table.getOrCreate(2, 20);

    Set<Integer> keys = new HashSet<>();
    ConcurrentHashtable.drain(table.buckets, keys, (ctx, e) -> ctx.add(e.key));

    assertEquals(new HashSet<>(java.util.Arrays.asList(1, 2)), keys);
    assertNull(table.get(1));
  }

  @Test
  void staticClearEmptiesEveryBucket() {
    IntTable table = new IntTable(8);
    table.getOrCreate(1, 1);
    table.getOrCreate(2, 2);
    ConcurrentHashtable.clear(table.buckets);
    assertNull(table.get(1));
    assertNull(table.get(2));
    for (int i = 0; i < table.buckets.length(); i++) {
      assertNull(table.buckets.get(i));
    }
  }

  @Test
  void staticForEachVisitsEveryEntry() {
    IntTable table = new IntTable(8);
    table.getOrCreate(1, 1);
    table.getOrCreate(2, 2);
    table.getOrCreate(3, 3);

    Set<Integer> seen = new HashSet<>();
    ConcurrentHashtable.forEach(table.buckets, e -> seen.add(e.key));
    assertEquals(new HashSet<>(java.util.Arrays.asList(1, 2, 3)), seen);

    Set<Integer> seenCtx = new HashSet<>();
    ConcurrentHashtable.forEach(table.buckets, seenCtx, (ctx, e) -> ctx.add(e.key));
    assertEquals(new HashSet<>(java.util.Arrays.asList(1, 2, 3)), seenCtx);
  }

  @Test
  void insertHeadEntryWithoutLockTripsAssertion() {
    assumeTrue(assertionsEnabled(), "assert-guard test requires -ea");
    AtomicReferenceArray<IntEntry> buckets =
        ConcurrentHashtable.createFixedBuckets(IntEntry.class, 8);
    assertThrows(
        AssertionError.class,
        () -> ConcurrentHashtable.insertHeadEntryAt(buckets, 0, new IntEntry(1, 1)));
  }

  @Test
  void unlinkWithoutLockTripsAssertion() {
    assumeTrue(assertionsEnabled(), "assert-guard test requires -ea");
    AtomicReferenceArray<IntEntry> buckets =
        ConcurrentHashtable.createFixedBuckets(IntEntry.class, 8);
    IntEntry e = new IntEntry(1, 1);
    synchronized (ConcurrentHashtable.getWriteLockAt(buckets, 0)) {
      ConcurrentHashtable.insertHeadEntryAt(buckets, 0, e);
    }
    assertThrows(AssertionError.class, () -> ConcurrentHashtable.unlink(buckets, 0, null, e));
  }

  @Test
  void concurrentGetOrCreateViaStaticsProducesExactlyOneEntry() throws InterruptedException {
    IntTable table = new IntTable(8);
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
                table.getOrCreateCounting(7, createCount);
              });
      workers[i].start();
    }
    ready.await();
    go.countDown();
    for (Thread w : workers) {
      w.join();
    }

    assertEquals(1, table.size.get());
    assertEquals(1, createCount.get());
  }

  @Test
  void concurrentReadsStaySafeWhileOneChainMemberChurnsViaStatics() throws InterruptedException {
    // Capacity 1 puts every key in one bucket so unlink splices a chain the reader is walking.
    IntTable table = new IntTable(1);
    int n = 8;
    for (int i = 0; i < n; i++) {
      table.getOrCreate(i, i);
    }
    int churn = 0; // keys 1..n-1 are stable and must never vanish

    AtomicBoolean stop = new AtomicBoolean(false);
    AtomicInteger missed = new AtomicInteger();
    Thread reader =
        new Thread(
            () -> {
              while (!stop.get()) {
                for (int i = 1; i < n; i++) {
                  if (table.get(i) == null) {
                    missed.incrementAndGet();
                  }
                }
              }
            });
    reader.start();
    for (int r = 0; r < 100_000; r++) {
      table.remove(churn);
      table.getOrCreate(churn, churn);
    }
    stop.set(true);
    reader.join();

    assertEquals(0, missed.get(), "stable chain members must never be unreachable during removal");
  }

  private static boolean assertionsEnabled() {
    boolean enabled = false;
    assert enabled = true;
    return enabled;
  }

  /** Primitive-{@code int}-key entry: no boxing, keyHash is the key itself. */
  private static final class IntEntry extends ConcurrentHashtable.Entry {
    final int key;
    final int value;

    IntEntry(int key, int value) {
      super(key);
      this.key = key;
      this.value = value;
    }

    boolean matches(int key) {
      return this.key == key;
    }
  }

  /**
   * Minimal hand-written table over a caller-owned {@link AtomicReferenceArray}, following the
   * documented recipe: lock-free pre-check, then re-check + mutate under {@code
   * getWriteLock(buckets, keyHash)}.
   */
  private static final class IntTable {
    final AtomicReferenceArray<IntEntry> buckets;
    final AtomicInteger size = new AtomicInteger();

    IntTable(int capacity) {
      this.buckets = ConcurrentHashtable.createFixedBuckets(IntEntry.class, capacity);
    }

    IntEntry get(int key) {
      for (IntEntry e = ConcurrentHashtable.bucketFor(buckets, (long) key);
          e != null;
          e = e.next()) {
        if (e.matches(key)) {
          return e;
        }
      }
      return null;
    }

    IntEntry getOrCreate(int key, int value) {
      int index = ConcurrentHashtable.bucketIndex(buckets, key);
      for (IntEntry e = ConcurrentHashtable.bucketAt(buckets, index); e != null; e = e.next()) {
        if (e.matches(key)) {
          return e;
        }
      }
      synchronized (ConcurrentHashtable.getWriteLockAt(buckets, index)) {
        for (IntEntry e = ConcurrentHashtable.bucketAt(buckets, index); e != null; e = e.next()) {
          if (e.matches(key)) {
            return e;
          }
        }
        IntEntry created = new IntEntry(key, value);
        ConcurrentHashtable.insertHeadEntryAt(buckets, index, created);
        size.incrementAndGet();
        return created;
      }
    }

    /** {@link #getOrCreate} variant that counts real creations, for the exactly-once race test. */
    IntEntry getOrCreateCounting(int key, AtomicInteger createCount) {
      int index = ConcurrentHashtable.bucketIndex(buckets, key);
      for (IntEntry e = ConcurrentHashtable.bucketAt(buckets, index); e != null; e = e.next()) {
        if (e.matches(key)) {
          return e;
        }
      }
      synchronized (ConcurrentHashtable.getWriteLockAt(buckets, index)) {
        for (IntEntry e = ConcurrentHashtable.bucketAt(buckets, index); e != null; e = e.next()) {
          if (e.matches(key)) {
            return e;
          }
        }
        createCount.incrementAndGet();
        IntEntry created = new IntEntry(key, 0);
        ConcurrentHashtable.insertHeadEntryAt(buckets, index, created);
        size.incrementAndGet();
        return created;
      }
    }

    IntEntry remove(int key) {
      int index = ConcurrentHashtable.bucketIndex(buckets, key);
      synchronized (ConcurrentHashtable.getWriteLockAt(buckets, index)) {
        IntEntry prev = null;
        for (IntEntry e = ConcurrentHashtable.bucketAt(buckets, index); e != null; e = e.next()) {
          if (e.matches(key)) {
            ConcurrentHashtable.unlink(buckets, index, prev, e);
            size.decrementAndGet();
            return e;
          }
          prev = e;
        }
        return null;
      }
    }
  }
}
