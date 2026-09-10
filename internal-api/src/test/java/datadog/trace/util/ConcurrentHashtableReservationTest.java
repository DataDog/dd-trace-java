package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;
import org.junit.jupiter.api.Test;

/** Exercises {@link ConcurrentHashtable#reserve} and {@link ConcurrentHashtable.Reservation}. */
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
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.reserve(state)) {
      assertTrue(r.isPresent());
      first = r.tryGetOrInsertOrNull(TestEntry::new, 1);
    }
    assertEquals(1, first.value);
    assertEquals(1, ConcurrentHashtable.estimateSize(state));

    // Reserving again for a key that already exists should discard the reservation and return the
    // existing entry, not double-insert or leak the claimed slot.
    TestEntry second;
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.reserve(state)) {
      second = r.tryGetOrInsertOrNull(TestEntry::new, 1);
    }
    assertSame(first, second);
    assertEquals(1, ConcurrentHashtable.estimateSize(state));
  }

  @Test
  void reserveOnFullTableIsAbsentAndSkipsTheFactory() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.createBounded(TestEntry.class, 1);
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.reserve(state)) {
      r.tryGetOrInsertOrNull(TestEntry::new, 1);
    }
    assertTrue(ConcurrentHashtable.isFull(state));

    AtomicInteger factoryCalls = new AtomicInteger();
    TestEntry result;
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.reserve(state)) {
      assertFalse(r.isPresent());
      result =
          r.tryGetOrInsertOrNull(
              v -> {
                factoryCalls.incrementAndGet();
                return new TestEntry(v);
              },
              2);
    }
    assertNull(result);
    assertEquals(0, factoryCalls.get());
    assertEquals(1, ConcurrentHashtable.estimateSize(state));
  }

  @Test
  void closeCancelsAnUnconsumedReservation() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.createBounded(TestEntry.class, 1);
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.reserve(state)) {
      assertTrue(r.isPresent());
      // Deliberately not consuming the reservation.
    }
    assertEquals(0, ConcurrentHashtable.estimateSize(state));
    assertFalse(ConcurrentHashtable.isFull(state));
  }

  @Test
  void closeOnAnAbsentReservationIsANoOp() {
    ConcurrentHashtable.State<TestEntry> state =
        ConcurrentHashtable.createBounded(TestEntry.class, 0);
    try (ConcurrentHashtable.Reservation<TestEntry> r = ConcurrentHashtable.reserve(state)) {
      assertFalse(r.isPresent());
    }
    assertEquals(0, ConcurrentHashtable.estimateSize(state));
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
    try (ConcurrentHashtable.Reservation<ThreePartEntry> r = ConcurrentHashtable.reserve(state3)) {
      three = r.tryGetOrInsertOrNull(ThreePartEntry::new, "x", "y", "z");
    }
    assertEquals("x", three.a);
    assertEquals("y", three.b);
    assertEquals("z", three.c);

    ConcurrentHashtable.State<FourPartEntry> state4 =
        ConcurrentHashtable.createBounded(FourPartEntry.class, 2);
    FourPartEntry four;
    try (ConcurrentHashtable.Reservation<FourPartEntry> r = ConcurrentHashtable.reserve(state4)) {
      four = r.tryGetOrInsertOrNull(FourPartEntry::new, "w", "x", "y", "z");
    }
    assertEquals("w", four.a);
    assertEquals("x", four.b);
    assertEquals("y", four.c);
    assertEquals("z", four.d);
  }
}
