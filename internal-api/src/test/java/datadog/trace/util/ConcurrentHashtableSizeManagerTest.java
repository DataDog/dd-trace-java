package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link ConcurrentHashtable.SizeManager} and {@link ConcurrentHashtable.State} against a
 * {@link ConcurrentHashtable.State}, the same shape a custom table driving the static building
 * blocks would use. {@link ConcurrentHashtableD1Test} and {@link ConcurrentHashtableD2Test} cover
 * the eviction-aware {@code tryGetOrCreateOrEvict} methods built on top of this.
 */
class ConcurrentHashtableSizeManagerTest {

  @Test
  void tryReserveSucceedsUnderCapacityAndFailsWhenFull() {
    ConcurrentHashtable.SizeManager sizeManager = new ConcurrentHashtable.SizeManager(2);
    assertEquals(0, sizeManager.estimateSize());
    assertFalse(sizeManager.isFull());

    assertTrue(sizeManager.tryReserve());
    assertEquals(1, sizeManager.estimateSize());
    assertFalse(sizeManager.isFull());

    assertTrue(sizeManager.tryReserve());
    assertEquals(2, sizeManager.estimateSize());
    assertTrue(sizeManager.isFull());

    assertFalse(sizeManager.tryReserve());
    assertEquals(2, sizeManager.estimateSize());
  }

  @Test
  void tryReserveOrEvictReservesDirectlyWhenUnderCapacity() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.State.createCapped(TestEntry.class, 2);

    boolean reserved = tryReserveOrEvict(state, e -> true);
    assertTrue(reserved);
    assertEquals(1, state.sizeManager.estimateSize());
    assertNull(state.buckets.get(0)); // nothing was evicted
  }

  @Test
  void tryReserveOrEvictEvictsWhenFullAndSomethingMatches() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.State.createCapped(TestEntry.class, 1);
    TestEntry existing = insertAt(state, 0, "existing");
    assertTrue(state.sizeManager.tryReserve());
    assertTrue(state.sizeManager.isFull());

    boolean reserved = tryReserveOrEvict(state, e -> true);
    assertTrue(reserved);
    assertEquals(1, state.sizeManager.estimateSize()); // one evicted, one reserved: net unchanged
    assertNull(state.buckets.get(0)); // existing was unlinked
  }

  @Test
  void tryReserveOrEvictFailsAndLeavesTableUntouchedWhenNothingEvictable() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.State.createCapped(TestEntry.class, 1);
    TestEntry existing = insertAt(state, 0, "existing");
    assertTrue(state.sizeManager.tryReserve());

    boolean reserved = tryReserveOrEvict(state, e -> false);
    assertFalse(reserved);
    assertEquals(1, state.sizeManager.estimateSize());
    assertSame(existing, state.buckets.get(0));
  }

  @Test
  void insertReservedSplicesWithoutTouchingTheCountAfterATryReserve() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.State.createCapped(TestEntry.class, 2);
    assertTrue(state.sizeManager.tryReserve());
    assertEquals(1, state.sizeManager.estimateSize());

    TestEntry entry = new TestEntry(0, "reserved");
    synchronized (ConcurrentHashtable.getWriteLock(state)) {
      ConcurrentHashtable.insertReserved(state, entry.keyHash, entry);
    }

    assertSame(entry, state.buckets.get(0));
    // Count reflects only the earlier tryReserve() -- insertReserved must not increment again.
    assertEquals(1, state.sizeManager.estimateSize());
  }

  @Test
  void evictOneReturnsNullAndLeavesCountUnchangedWhenNothingMatches() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.State.createCapped(TestEntry.class, 4);
    insertAt(state, 0, "a");
    state.sizeManager.increment();

    TestEntry evicted = evictOne(state, e -> false);
    assertNull(evicted);
    assertEquals(1, state.sizeManager.estimateSize());
  }

  @Test
  void evictOneUnlinksMatchAndDecrementsCount() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.State.createCapped(TestEntry.class, 4);
    TestEntry a = insertAt(state, 0, "a");
    TestEntry b = insertAt(state, 1, "b");
    state.sizeManager.increment();
    state.sizeManager.increment();

    TestEntry evicted = evictOne(state, e -> e.label.equals("a"));
    assertSame(a, evicted);
    assertNull(state.buckets.get(0));
    assertSame(b, state.buckets.get(1)); // untouched
    assertEquals(1, state.sizeManager.estimateSize());
  }

  /**
   * Verifies the cursor-resume contract from {@link ConcurrentHashtable.SizeManager#evictOne}: each
   * scan resumes where the previous eviction left off, so among several equally-matching candidates
   * the one nearest (forward from the cursor, wrapping) is picked first -- not always the lowest
   * bucket index.
   */
  @Test
  void evictOneResumesFromLastEvictedBucketAndWrapsAround() {
    // Bucket-array length 4: keyHash i lands in bucket i.
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.State.createCapped(TestEntry.class, 4);
    TestEntry e0 = insertAt(state, 0, "e0");
    insertAt(state, 2, "e2");
    TestEntry e3 = insertAt(state, 3, "e3");
    state.sizeManager.increment();
    state.sizeManager.increment();
    state.sizeManager.increment();

    // First eviction: scan starts at cursor 0, finds bucket 2 first among evictable entries
    // (only e2 matches here) -- sets the cursor to 2.
    TestEntry firstEvicted = evictOne(state, e -> e.label.equals("e2"));
    assertEquals("e2", firstEvicted.label);

    // Second eviction: both e0 (bucket 0) and e3 (bucket 3) match. Scanning resumes at the
    // cursor (2) and goes forward before wrapping, so bucket 3 (e3) is found before bucket 0.
    TestEntry secondEvicted = evictOne(state, e -> true);
    assertSame(e3, secondEvicted);
    assertSame(e0, state.buckets.get(0)); // e0 not yet touched

    // Third eviction: only e0 remains. The cursor is now past bucket 3, so the scan must wrap
    // around to bucket 0 to find it.
    TestEntry thirdEvicted = evictOne(state, e -> true);
    assertSame(e0, thirdEvicted);
    assertEquals(0, state.sizeManager.estimateSize());
  }

  @Test
  void evictAllRemovesEveryMatchAndReturnsCount() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.State.createCapped(TestEntry.class, 8);
    for (int i = 0; i < 6; i++) {
      insertAt(state, i, "e" + i);
      state.sizeManager.increment();
    }
    // Evict everything except bucket 1 and bucket 4.
    int count = evictAll(state, e -> !e.label.equals("e1") && !e.label.equals("e4"));

    assertEquals(4, count);
    assertEquals(2, state.sizeManager.estimateSize());
    assertNotNullLabel(state, 1, "e1");
    assertNotNullLabel(state, 4, "e4");
    for (int i : new int[] {0, 2, 3, 5}) {
      assertNull(state.buckets.get(i));
    }
  }

  @Test
  void evictAllResetsCursorSoSubsequentEvictOneScansFromBucketZero() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.State.createCapped(TestEntry.class, 4);
    insertAt(state, 2, "a");
    state.sizeManager.increment();
    // Advance the cursor away from 0 via a successful eviction at bucket 2.
    evictOne(state, e -> true);

    // A full pass that removes nothing still resets the scan position (per evictAll's contract).
    int count = evictAll(state, e -> false);
    assertEquals(0, count);

    TestEntry e0 = insertAt(state, 0, "e0");
    TestEntry e3 = insertAt(state, 3, "e3");
    state.sizeManager.increment();
    state.sizeManager.increment();

    // With the cursor reset to 0, the forward scan reaches bucket 0 before bucket 3.
    TestEntry evicted = evictOne(state, e -> true);
    assertSame(e0, evicted);
    assertSame(e3, state.buckets.get(3));
  }

  @Test
  void resetZeroesCountAndScanPosition() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.State.createCapped(TestEntry.class, 4);
    insertAt(state, 2, "a");
    state.sizeManager.increment();
    evictOne(state, e -> true); // advances the cursor to 2, count back to 0
    state.sizeManager.increment(); // pretend a fresh entry was inserted

    state.sizeManager.reset();
    assertEquals(0, state.sizeManager.estimateSize());

    TestEntry e0 = insertAt(state, 0, "e0");
    TestEntry e3 = insertAt(state, 3, "e3");
    state.sizeManager.increment();
    state.sizeManager.increment();
    TestEntry evicted = evictOne(state, e -> true);
    assertSame(e0, evicted); // scan restarted from bucket 0, per reset()
    assertSame(e3, state.buckets.get(3));
  }

  @Test
  void stateCreateCappedBundlesBucketsAndSizeManager() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.State.createCapped(TestEntry.class, 3);
    assertEquals(0, state.sizeManager.estimateSize());
    assertEquals(3, state.sizeManager.capacity());
    assertTrue(state.buckets.length() >= 3);
  }

  @Test
  void stateLevelTryReserveOrEvictAndEvictOneAndEvictAllDelegateToSizeManager() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.State.createCapped(TestEntry.class, 1);
    synchronized (ConcurrentHashtable.getWriteLock(state)) {
      ConcurrentHashtable.insertHeadEntryAt(state, 0, new TestEntry(0, "a"));
    }
    state.sizeManager.increment();
    assertTrue(ConcurrentHashtable.isFull(state));

    // Table is full: tryReserveOrEvict evicts "a" and reserves the freed slot for the caller, who
    // is now responsible for splicing in the entry that occupies it -- via insertReserved, since
    // the reservation already happened and a plain insertHeadEntryAt/increment would double-count.
    boolean reserved = ConcurrentHashtable.tryReserveOrEvict(state, e -> true);
    assertTrue(reserved);
    assertEquals(1, ConcurrentHashtable.estimateSize(state));
    assertNull(state.buckets.get(0)); // "a" was evicted; the reserved slot has no entry yet
    synchronized (ConcurrentHashtable.getWriteLock(state)) {
      ConcurrentHashtable.insertReserved(state, 0, new TestEntry(0, "reserved"));
    }

    int evicted = ConcurrentHashtable.evictAll(state, e -> true);
    assertEquals(1, evicted);
    assertEquals(0, ConcurrentHashtable.estimateSize(state));
    assertFalse(ConcurrentHashtable.isFull(state));

    synchronized (ConcurrentHashtable.getWriteLock(state)) {
      ConcurrentHashtable.insertHeadEntryAt(state, 0, new TestEntry(0, "b"));
    }
    state.sizeManager.increment();
    TestEntry viaEvictOne = ConcurrentHashtable.evictOne(state, e -> e.label.equals("b"));
    assertNotNull(viaEvictOne);
    assertEquals("b", viaEvictOne.label);
    assertEquals(0, ConcurrentHashtable.estimateSize(state));
  }

  private static void assertNotNullLabel(
      ConcurrentHashtable.State<TestEntry> state, int index, String label) {
    TestEntry e = state.buckets.get(index);
    assertNotNull(e);
    assertEquals(label, e.label);
  }

  /** Inserts a fresh entry at the given bucket index. Bucket-array length must exceed index. */
  private static TestEntry insertAt(
      ConcurrentHashtable.State<TestEntry> state, int index, String label) {
    TestEntry entry = new TestEntry(index, label);
    synchronized (ConcurrentHashtable.getWriteLock(state)) {
      ConcurrentHashtable.insertHeadEntryAt(state, index, entry);
    }
    return entry;
  }

  /** {@code sizeManager.tryReserveOrEvict}, taking the write lock {@code @GuardedBy} requires. */
  private static boolean tryReserveOrEvict(
      ConcurrentHashtable.State<TestEntry> state, Predicate<TestEntry> evictable) {
    synchronized (ConcurrentHashtable.getWriteLock(state)) {
      return state.sizeManager.tryReserveOrEvict(state.buckets, evictable);
    }
  }

  /** {@code sizeManager.evictOne}, taking the write lock {@code @GuardedBy} requires. */
  private static TestEntry evictOne(
      ConcurrentHashtable.State<TestEntry> state, Predicate<TestEntry> evictable) {
    synchronized (ConcurrentHashtable.getWriteLock(state)) {
      return state.sizeManager.evictOne(state.buckets, evictable);
    }
  }

  /** {@code sizeManager.evictAll}, taking the write lock {@code @GuardedBy} requires. */
  private static int evictAll(
      ConcurrentHashtable.State<TestEntry> state, Predicate<TestEntry> evictable) {
    synchronized (ConcurrentHashtable.getWriteLock(state)) {
      return state.sizeManager.evictAll(state.buckets, evictable);
    }
  }

  /** Entry with a caller-controlled {@code keyHash} so tests can place it in an exact bucket. */
  private static final class TestEntry extends ConcurrentHashtable.Entry {
    final String label;

    TestEntry(long keyHash, String label) {
      super(keyHash);
      this.label = label;
    }
  }
}
