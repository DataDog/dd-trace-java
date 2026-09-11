package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.Test;

/** Exercises {@link ConcurrentHashtable#tryReserve} and {@link ConcurrentHashtable.Reservation}. */
class ConcurrentHashtableReservationTest {

  private static final class TestEntry extends ConcurrentHashtable.Entry<TestEntry> {
    final int value;

    TestEntry(int value) {
      super(value);
      this.value = value;
    }

    @Override
    public boolean matches(@Nonnull TestEntry other) {
      return value == other.value;
    }
  }

  @Test
  void tryGetOrInsertOrNullInsertsOnMissAndFindsOnHit() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.createBounded(TestEntry.class, 4);

    TestEntry first;
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.tryReserve(state)) {
      assertTrue(r.isReserved());
      first = r.tryGetOrInsertOrNull(1, TestEntry::new);
    }
    assertEquals(1, first.value);
    assertEquals(1, ConcurrentHashtable.estimateSize(state));

    // Reserving again for a key that already exists should discard the reservation and return the
    // existing entry, not double-insert or leak the claimed slot.
    TestEntry second;
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.tryReserve(state)) {
      second = r.tryGetOrInsertOrNull(1, TestEntry::new);
    }
    assertSame(first, second);
    assertEquals(1, ConcurrentHashtable.estimateSize(state));
  }

  @Test
  void reserveOnFullTableIsAbsentAndSkipsTheFactory() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.createBounded(TestEntry.class, 1);
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.tryReserve(state)) {
      r.tryGetOrInsertOrNull(1, TestEntry::new);
    }
    assertTrue(ConcurrentHashtable.isFull(state));

    AtomicInteger factoryCalls = new AtomicInteger();
    TestEntry result;
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.tryReserve(state)) {
      assertFalse(r.isReserved());
      result =
          r.tryGetOrInsertOrNull(
              2,
              v -> {
                factoryCalls.incrementAndGet();
                return new TestEntry(v);
              });
    }
    assertNull(result);
    assertEquals(0, factoryCalls.get());
    assertEquals(1, ConcurrentHashtable.estimateSize(state));
  }

  @Test
  void closeCancelsAnUnconsumedReservation() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.createBounded(TestEntry.class, 1);
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.tryReserve(state)) {
      assertTrue(r.isReserved());
      // Deliberately not consuming the reservation.
    }
    assertEquals(0, ConcurrentHashtable.estimateSize(state));
    assertFalse(ConcurrentHashtable.isFull(state));
  }

  @Test
  void tryGetOrInsertWrapsResultInMaybe() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.createBounded(TestEntry.class, 1);

    Maybe<TestEntry> present;
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.tryReserve(state)) {
      present = r.tryGetOrInsert(1, TestEntry::new);
    }
    assertTrue(present.isPresent());
    assertEquals(1, present.getOrNull().value);

    Maybe<TestEntry> absent;
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.tryReserve(state)) {
      absent = r.tryGetOrInsert(2, TestEntry::new);
    }
    assertFalse(absent.isPresent());
    assertNull(absent.getOrNull());
  }

  @Test
  void closeOnAnAbsentReservationIsANoOp() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.createBounded(TestEntry.class, 0);
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.tryReserve(state)) {
      assertFalse(r.isReserved());
    }
    assertEquals(0, ConcurrentHashtable.estimateSize(state));
  }

  private static final class TwoPartEntry extends ConcurrentHashtable.Entry<TwoPartEntry> {
    final String a;
    final String b;

    TwoPartEntry(String a, String b) {
      super(HashingUtils.hash(a, b));
      this.a = a;
      this.b = b;
    }

    @Override
    public boolean matches(@Nonnull TwoPartEntry other) {
      return a.equals(other.a) && b.equals(other.b);
    }
  }

  private static final class ThreePartEntry extends ConcurrentHashtable.Entry<ThreePartEntry> {
    final String a;
    final String b;
    final String c;

    ThreePartEntry(String a, String b, String c) {
      super(HashingUtils.hash(a, b, c));
      this.a = a;
      this.b = b;
      this.c = c;
    }

    @Override
    public boolean matches(@Nonnull ThreePartEntry other) {
      return a.equals(other.a) && b.equals(other.b) && c.equals(other.c);
    }
  }

  private static final class FourPartEntry extends ConcurrentHashtable.Entry<FourPartEntry> {
    final String a;
    final String b;
    final String c;
    final String d;

    FourPartEntry(String a, String b, String c, String d) {
      super(HashingUtils.hash(a, b, c, d));
      this.a = a;
      this.b = b;
      this.c = c;
      this.d = d;
    }

    @Override
    public boolean matches(@Nonnull FourPartEntry other) {
      return a.equals(other.a) && b.equals(other.b) && c.equals(other.c) && d.equals(other.d);
    }
  }

  @Test
  void tryGetOrInsertOrNullSupportsUpToFourComponents() {
    ConcurrentHashtable.State<ThreePartEntry> state3 =
        ConcurrentHashtable.createBounded(ThreePartEntry.class, 2);
    ThreePartEntry three;
    try (ConcurrentHashtable.Reservation<ThreePartEntry> r =
        ConcurrentHashtable.tryReserve(state3)) {
      three = r.tryGetOrInsertOrNull("x", "y", "z", ThreePartEntry::new);
    }
    assertEquals("x", three.a);
    assertEquals("y", three.b);
    assertEquals("z", three.c);

    ConcurrentHashtable.State<FourPartEntry> state4 =
        ConcurrentHashtable.createBounded(FourPartEntry.class, 2);
    FourPartEntry four;
    try (ConcurrentHashtable.Reservation<FourPartEntry> r =
        ConcurrentHashtable.tryReserve(state4)) {
      four = r.tryGetOrInsertOrNull("w", "x", "y", "z", FourPartEntry::new);
    }
    assertEquals("w", four.a);
    assertEquals("x", four.b);
    assertEquals("y", four.c);
    assertEquals("z", four.d);
  }

  @Test
  void tryGetOrInsertOrNullSupportsTwoComponents() {
    ConcurrentHashtable.State<TwoPartEntry> state =
        ConcurrentHashtable.createBounded(TwoPartEntry.class, 2);
    TwoPartEntry two;
    try (ConcurrentHashtable.Reservation<TwoPartEntry> r = ConcurrentHashtable.tryReserve(state)) {
      two = r.tryGetOrInsertOrNull("x", "y", TwoPartEntry::new);
    }
    assertEquals("x", two.a);
    assertEquals("y", two.b);
  }

  @Test
  void tryGetOrInsertOrNullOnPrebuiltEntry() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.createBounded(TestEntry.class, 1);
    TestEntry inserted;
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.tryReserve(state)) {
      inserted = r.tryGetOrInsertOrNull(new TestEntry(1));
    }
    assertEquals(1, inserted.value);
    assertEquals(1, ConcurrentHashtable.estimateSize(state));
  }

  @Test
  void tryGetOrInsertOnPrebuiltEntryWrapsResultInMaybe() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.createBounded(TestEntry.class, 1);

    Maybe<TestEntry> present;
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.tryReserve(state)) {
      present = r.tryGetOrInsert(new TestEntry(1));
    }
    assertTrue(present.isPresent());
    assertEquals(1, present.getOrNull().value);

    Maybe<TestEntry> absent;
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.tryReserve(state)) {
      absent = r.tryGetOrInsert(new TestEntry(2));
    }
    assertFalse(absent.isPresent());
    assertNull(absent.getOrNull());
  }

  @Test
  void tryGetOrInsertWrapsResultInMaybeForTwoThreeAndFourComponents() {
    ConcurrentHashtable.State<TwoPartEntry> state2 =
        ConcurrentHashtable.createBounded(TwoPartEntry.class, 2);
    Maybe<TwoPartEntry> two;
    try (ConcurrentHashtable.Reservation<TwoPartEntry> r = ConcurrentHashtable.tryReserve(state2)) {
      two = r.tryGetOrInsert("x", "y", TwoPartEntry::new);
    }
    assertTrue(two.isPresent());
    assertEquals("x", two.getOrNull().a);
    assertEquals("y", two.getOrNull().b);

    ConcurrentHashtable.State<ThreePartEntry> state3 =
        ConcurrentHashtable.createBounded(ThreePartEntry.class, 2);
    Maybe<ThreePartEntry> three;
    try (ConcurrentHashtable.Reservation<ThreePartEntry> r =
        ConcurrentHashtable.tryReserve(state3)) {
      three = r.tryGetOrInsert("x", "y", "z", ThreePartEntry::new);
    }
    assertTrue(three.isPresent());
    assertEquals("x", three.getOrNull().a);
    assertEquals("y", three.getOrNull().b);
    assertEquals("z", three.getOrNull().c);

    ConcurrentHashtable.State<FourPartEntry> state4 =
        ConcurrentHashtable.createBounded(FourPartEntry.class, 2);
    Maybe<FourPartEntry> four;
    try (ConcurrentHashtable.Reservation<FourPartEntry> r =
        ConcurrentHashtable.tryReserve(state4)) {
      four = r.tryGetOrInsert("w", "x", "y", "z", FourPartEntry::new);
    }
    assertTrue(four.isPresent());
    assertEquals("w", four.getOrNull().a);
    assertEquals("x", four.getOrNull().b);
    assertEquals("y", four.getOrNull().c);
    assertEquals("z", four.getOrNull().d);
  }

  @Test
  void tryReserveForSkipsTheLockWhenTheTargetBucketIsDefinitelyEmpty() {
    // capacity 3 rounds up to 4 buckets, so one bucket stays empty once the table is full.
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.createBounded(TestEntry.class, 3);
    for (int keyHash = 1; keyHash <= 3; keyHash++) {
      try (ConcurrentHashtable.Reservation<TestEntry> r =
          ConcurrentHashtable.tryReserveFor(state, keyHash)) {
        r.tryGetOrInsertOrNull(keyHash, TestEntry::new);
      }
    }
    assertTrue(ConcurrentHashtable.isFull(state));

    AtomicInteger factoryCalls = new AtomicInteger();
    TestEntry result;
    try (ConcurrentHashtable.Reservation<TestEntry> r =
        ConcurrentHashtable.tryReserveFor(state, 4)) {
      assertFalse(r.isReserved());
      result =
          r.tryGetOrInsertOrNull(
              4,
              v -> {
                factoryCalls.incrementAndGet();
                return new TestEntry(v);
              });
    }
    assertNull(result);
    assertEquals(0, factoryCalls.get());
    assertEquals(3, ConcurrentHashtable.estimateSize(state));
  }

  @Test
  void tryReserveForFindsAConcurrentDuplicateEvenWhenTheTableLooksFull() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.createBounded(TestEntry.class, 3);
    TestEntry existing;
    try (ConcurrentHashtable.Reservation<TestEntry> r =
        ConcurrentHashtable.tryReserveFor(state, 1)) {
      existing = r.tryGetOrInsertOrNull(1, TestEntry::new);
    }
    try (ConcurrentHashtable.Reservation<TestEntry> r =
        ConcurrentHashtable.tryReserveFor(state, 2)) {
      r.tryGetOrInsertOrNull(2, TestEntry::new);
    }
    try (ConcurrentHashtable.Reservation<TestEntry> r =
        ConcurrentHashtable.tryReserveFor(state, 3)) {
      r.tryGetOrInsertOrNull(3, TestEntry::new);
    }
    assertTrue(ConcurrentHashtable.isFull(state));

    // Reserving for the same keyHash again simulates a concurrent duplicate insert landing just
    // before the caller's own reservation attempt.
    TestEntry match;
    try (ConcurrentHashtable.Reservation<TestEntry> r =
        ConcurrentHashtable.tryReserveFor(state, 1)) {
      assertTrue(r.isReserved());
      match = r.tryGetOrInsertOrNull(new TestEntry(1));
    }
    assertSame(existing, match);
    assertEquals(3, ConcurrentHashtable.estimateSize(state));
  }

  @Test
  void tryReserveForReturnsNullWithoutOvercountingWhenGenuinelyFull() {
    // capacity 3 rounds up to 4 buckets; keyHash 1 and 5 collide on the same bucket (index 1) but
    // are logically distinct keys (TestEntry.matches compares by value).
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.createBounded(TestEntry.class, 3);
    for (int keyHash = 1; keyHash <= 3; keyHash++) {
      try (ConcurrentHashtable.Reservation<TestEntry> r =
          ConcurrentHashtable.tryReserveFor(state, keyHash)) {
        r.tryGetOrInsertOrNull(keyHash, TestEntry::new);
      }
    }
    assertTrue(ConcurrentHashtable.isFull(state));

    TestEntry result;
    try (ConcurrentHashtable.Reservation<TestEntry> r =
        ConcurrentHashtable.tryReserveFor(state, 5)) {
      assertTrue(r.isReserved());
      result = r.tryGetOrInsertOrNull(new TestEntry(5));
    }
    assertNull(result);
    assertEquals(3, ConcurrentHashtable.estimateSize(state));
  }
}
