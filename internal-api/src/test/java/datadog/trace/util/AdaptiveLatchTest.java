package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AdaptiveLatchTest {

  /**
   * Parses an int. The cautious path accepts only plain digits and rejects anything else, so
   * "sneaky" input such as an overflowing number passes neither path; "1_000" is repaired by the
   * cautious path, which the optimistic path rejects.
   */
  private static final class Parsing extends AdaptiveLatch<String, Integer, NumberFormatException> {
    final AtomicInteger optimistic = new AtomicInteger();
    final AtomicInteger cautious = new AtomicInteger();

    Parsing() {
      super(NumberFormatException.class, 3);
    }

    @Override
    protected Integer apply(String input) {
      optimistic.incrementAndGet();
      if ("boom".equals(input)) {
        throw new IllegalStateException("boom");
      }
      return Integer.parseInt(input);
    }

    @Override
    protected Integer applySafely(String input) {
      cautious.incrementAndGet();
      String digits = input.replace("_", "");
      if (digits.isEmpty() || digits.length() > 9) {
        return reject(input);
      }
      int value = 0;
      for (int i = 0; i < digits.length(); i++) {
        char c = digits.charAt(i);
        if (c < '0' || c > '9') {
          return reject(input);
        }
        value = value * 10 + (c - '0');
      }
      return value;
    }
  }

  @Test
  void disengagedItTakesOnlyTheOptimisticPath() {
    Parsing latch = new Parsing();

    assertEquals(42, latch.tryApply("42"));
    assertEquals(7, latch.tryApply("7"));

    assertEquals(2, latch.optimistic.get());
    assertEquals(0, latch.cautious.get());
    assertFalse(latch.isEngaged());
  }

  @Test
  void anOptimisticFailureEngagesAndRetriesTheSameInputCautiously() {
    Parsing latch = new Parsing();

    assertNull(latch.tryApply("bad"));

    assertEquals(1, latch.optimistic.get());
    assertEquals(1, latch.cautious.get());
    assertTrue(latch.isEngaged());
  }

  @Test
  void theCautiousRetryCanRepairWhatTheOptimisticPathRejected() {
    Parsing latch = new Parsing();

    assertEquals(1000, latch.tryApply("1_000"));
    assertTrue(latch.isEngaged());
  }

  @Test
  void engagedItTakesOnlyTheCautiousPath() {
    Parsing latch = new Parsing();
    latch.tryApply("bad");

    assertEquals(42, latch.tryApply("42"));
    assertNull(latch.tryApply("bad"));

    assertEquals(1, latch.optimistic.get(), "no optimistic call, and so no throw, while engaged");
    assertEquals(3, latch.cautious.get());
  }

  @Test
  void disengagesAfterEnoughConsecutiveCleanCalls() {
    Parsing latch = new Parsing();
    latch.tryApply("bad");

    latch.tryApply("1");
    latch.tryApply("2");
    assertTrue(latch.isEngaged());
    latch.tryApply("3");
    assertFalse(latch.isEngaged());

    assertEquals(4, latch.tryApply("4"));
    assertEquals(2, latch.optimistic.get(), "disengaged again, so back on the optimistic path");
  }

  @Test
  void aRejectionWhileEngagedRestartsTheCount() {
    Parsing latch = new Parsing();
    latch.tryApply("bad");
    latch.tryApply("1");
    latch.tryApply("2");

    assertNull(latch.tryApply("bad"));

    latch.tryApply("3");
    latch.tryApply("4");
    assertTrue(latch.isEngaged(), "the count restarted from closeAfter");
    latch.tryApply("5");
    assertFalse(latch.isEngaged());
  }

  @Test
  void otherExceptionsPropagateWithoutEngaging() {
    Parsing latch = new Parsing();

    assertThrows(IllegalStateException.class, () -> latch.tryApply("boom"));
    assertFalse(latch.isEngaged());
    assertEquals(0, latch.cautious.get());
  }

  @Test
  void rejectYieldsTheFallbackAndARealNullCountsAsClean() {
    AdaptiveLatch<String, String, IllegalArgumentException> latch =
        new AdaptiveLatch<String, String, IllegalArgumentException>(
            IllegalArgumentException.class, 1) {
          @Override
          protected String apply(String input) {
            if (input.isEmpty()) {
              throw new IllegalArgumentException();
            }
            return "null".equals(input) ? null : input;
          }

          @Override
          protected String applySafely(String input) {
            if (input.isEmpty()) {
              return reject(input);
            }
            return "null".equals(input) ? null : input;
          }

          @Override
          protected String fallback(String input) {
            return "fallback";
          }
        };

    // failed optimistically, then rejected cautiously
    assertEquals("fallback", latch.tryApply(""));
    // rejected while engaged
    assertEquals("fallback", latch.tryApply(""));
    // a cautious call that succeeds with null is clean, and with closeAfter 1 disengages
    assertNull(latch.tryApply("null"));
    assertFalse(latch.isEngaged());
  }

  @Test
  void closeAfterMustBeAtLeastOne() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new AdaptiveLatch<String, String, IllegalArgumentException>(
                IllegalArgumentException.class, 0) {
              @Override
              protected String apply(String input) {
                return input;
              }

              @Override
              protected String applySafely(String input) {
                return input;
              }
            });
  }

  /**
   * Parses a URI, a checked failure. The cautious path repairs spaces and reports each repair, so
   * repairable input keeps the latch engaged.
   */
  private static final class UriParsing extends AdaptiveLatch<String, URI, URISyntaxException> {
    final AtomicInteger optimistic = new AtomicInteger();

    UriParsing() {
      super(URISyntaxException.class, 2);
    }

    @Override
    protected URI apply(String input) throws URISyntaxException {
      optimistic.incrementAndGet();
      return new URI(input);
    }

    @Override
    protected URI applySafely(String input) {
      String escaped = input.replace(" ", "%20");
      try {
        URI uri = new URI(escaped);
        return escaped.equals(input) ? uri : repaired(uri);
      } catch (URISyntaxException e) {
        return reject(input);
      }
    }
  }

  @Test
  void aCheckedFailureEngagesTheLatch() {
    UriParsing latch = new UriParsing();

    assertEquals(URI.create("/a%20b"), latch.tryApply("/a b"));
    assertTrue(latch.isEngaged());
  }

  @Test
  void repairedInputKeepsTheLatchEngaged() {
    UriParsing latch = new UriParsing();
    latch.tryApply("/a b");

    for (int i = 0; i < 5; i++) {
      assertEquals(URI.create("/c%20d"), latch.tryApply("/c d"));
    }

    assertTrue(latch.isEngaged(), "each repair restarts the count");
    assertEquals(1, latch.optimistic.get(), "only the first bad input reached the optimistic path");
  }

  @Test
  void cleanInputAfterRepairsStillDisengages() {
    UriParsing latch = new UriParsing();
    latch.tryApply("/a b");

    latch.tryApply("/clean");
    latch.tryApply("/clean");

    assertFalse(latch.isEngaged());
  }
}
