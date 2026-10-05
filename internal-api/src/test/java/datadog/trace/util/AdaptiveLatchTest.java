package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AdaptiveLatchTest {

  /**
   * Parses an int. "bad" is known to fail; "sneaky" also fails but passes the pre-check, as input
   * the pre-check is too lenient for would.
   */
  private static final class Parsing extends AdaptiveLatch<String, Integer, NumberFormatException> {
    final AtomicInteger applies = new AtomicInteger();
    final AtomicInteger checks = new AtomicInteger();

    Parsing() {
      super(NumberFormatException.class);
    }

    @Override
    protected Integer apply(String input) {
      applies.incrementAndGet();
      if ("boom".equals(input)) {
        throw new IllegalStateException("boom");
      }
      return Integer.parseInt(input);
    }

    @Override
    protected boolean isKnownToFail(String input) {
      checks.incrementAndGet();
      return "bad".equals(input);
    }

    @Override
    protected int closeAfter() {
      return 3;
    }
  }

  @Test
  void disengagedItAppliesEverythingWithoutPreChecking() {
    Parsing latch = new Parsing();

    assertEquals(42, latch.tryApply("42"));
    assertEquals(7, latch.tryApply("7"));

    assertEquals(2, latch.applies.get());
    assertEquals(0, latch.checks.get(), "the pre-check is only consulted while engaged");
    assertFalse(latch.isEngaged());
  }

  @Test
  void aFailureYieldsTheFallbackAndEngages() {
    Parsing latch = new Parsing();

    assertNull(latch.tryApply("bad"));

    assertEquals(1, latch.applies.get(), "disengaged, even known-bad input is attempted");
    assertTrue(latch.isEngaged());
  }

  @Test
  void engagedItTurnsAwayKnownBadInputWithoutApplying() {
    Parsing latch = new Parsing();
    latch.tryApply("bad");

    assertNull(latch.tryApply("bad"));
    assertNull(latch.tryApply("bad"));

    assertEquals(1, latch.applies.get());
    assertTrue(latch.isEngaged(), "turned-away input does not count towards disengaging");
  }

  @Test
  void engagedItStillAppliesInputThatPassesThePreCheck() {
    Parsing latch = new Parsing();
    latch.tryApply("bad");

    assertEquals(42, latch.tryApply("42"));
    assertEquals(2, latch.applies.get());
  }

  @Test
  void disengagesAfterEnoughConsecutiveSuccesses() {
    Parsing latch = new Parsing();
    latch.tryApply("bad");

    latch.tryApply("1");
    latch.tryApply("2");
    assertTrue(latch.isEngaged());
    latch.tryApply("3");

    assertFalse(latch.isEngaged());
  }

  @Test
  void aFailureThePreCheckMissesReArmsTheLatch() {
    Parsing latch = new Parsing();
    latch.tryApply("bad");
    latch.tryApply("1");
    latch.tryApply("2");

    // fails, but the pre-check is too lenient to know it
    assertNull(latch.tryApply("sneaky"));

    latch.tryApply("3");
    latch.tryApply("4");
    assertTrue(latch.isEngaged(), "the countdown restarted from closeAfter");
  }

  @Test
  void otherExceptionsPropagateWithoutEngaging() {
    Parsing latch = new Parsing();

    assertThrows(IllegalStateException.class, () -> latch.tryApply("boom"));
    assertFalse(latch.isEngaged());
  }

  @Test
  void fallbackIsUsedForFailedAndTurnedAwayInputButNotARealNull() {
    AdaptiveLatch<String, String, IllegalArgumentException> latch =
        new AdaptiveLatch<String, String, IllegalArgumentException>(
            IllegalArgumentException.class) {
          @Override
          protected String apply(String input) {
            if (input.isEmpty()) {
              throw new IllegalArgumentException();
            }
            return "null".equals(input) ? null : input;
          }

          @Override
          protected boolean isKnownToFail(String input) {
            return input.isEmpty();
          }

          @Override
          protected String fallback(String input) {
            return "fallback";
          }
        };

    // failed
    assertEquals("fallback", latch.tryApply(""));
    // turned away while engaged
    assertEquals("fallback", latch.tryApply(""));
    // an operation that succeeds with null is not a failure
    assertNull(latch.tryApply("null"));
  }

  @Test
  void closeAfterDefaultsToTheDefaultConstant() {
    AdaptiveLatch<String, String, IllegalArgumentException> latch =
        new AdaptiveLatch<String, String, IllegalArgumentException>(
            IllegalArgumentException.class) {
          @Override
          protected String apply(String input) {
            if (input.isEmpty()) {
              throw new IllegalArgumentException();
            }
            return input;
          }

          @Override
          protected boolean isKnownToFail(String input) {
            return input.isEmpty();
          }
        };
    latch.tryApply("");

    for (int i = 1; i < AdaptiveLatch.DEFAULT_CLOSE_AFTER; i++) {
      latch.tryApply("ok");
    }
    assertTrue(latch.isEngaged());
    latch.tryApply("ok");
    assertFalse(latch.isEngaged());
  }
}
