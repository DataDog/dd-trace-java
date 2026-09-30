package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class LatchTest {

  /** The shape of a missing-field read: rethrow the first failure, then skip the read. */
  private static final class FieldLatch extends Latch<String, Boolean, RuntimeException> {
    final AtomicInteger calls = new AtomicInteger();
    boolean fieldPresent;

    @Override
    protected Boolean get(String target) {
      calls.incrementAndGet();
      try {
        if (!fieldPresent) {
          throw new NoSuchFieldError("_interner");
        }
        return false;
      } catch (NoSuchFieldError e) {
        latch();
        throw e;
      }
    }
  }

  @Test
  void performsTheOperationUntilLatched() {
    FieldLatch latch = new FieldLatch();
    latch.fieldPresent = true;

    assertEquals(false, latch.tryGetOrNull("x"));
    assertEquals(false, latch.tryGetOrNull("x"));

    assertEquals(2, latch.calls.get());
    assertFalse(latch.isLatched());
  }

  @Test
  void rethrowsTheFirstFailureThenSkipsTheOperation() {
    FieldLatch latch = new FieldLatch();

    assertThrows(NoSuchFieldError.class, () -> latch.tryGetOrNull("x"));
    assertTrue(latch.isLatched());

    assertNull(latch.tryGetOrNull("x"));
    assertNull(latch.tryGetOrNull("y"));
    assertEquals(1, latch.calls.get(), "later calls should be skipped");
  }

  @Test
  void tryGetOrDefaultReturnsTheResultWhenThereIsOne() {
    FieldLatch latch = new FieldLatch();
    latch.fieldPresent = true;

    // a real false must not be replaced by the fallback
    assertEquals(false, latch.tryGetOrDefault("x", Boolean.TRUE));
  }

  @Test
  void tryGetOrDefaultReturnsTheFallbackOnceLatched() {
    FieldLatch latch = new FieldLatch();
    assertThrows(NoSuchFieldError.class, () -> latch.tryGetOrDefault("x", Boolean.TRUE));

    assertEquals(true, latch.tryGetOrDefault("x", Boolean.TRUE));
    assertEquals(true, latch.tryGetOrDefault("y", Boolean.TRUE));
    assertEquals(1, latch.calls.get(), "later calls should be skipped");
  }

  @Test
  void aCallThatYieldsNothingAndASkippedCallAgree() {
    // the first call latches and returns null; the next is skipped. Both must give the fallback.
    Latch<String, String, RuntimeException> latch =
        new Latch<String, String, RuntimeException>() {
          @Override
          protected String get(String target) {
            latch();
            return null;
          }
        };

    assertEquals("fallback", latch.tryGetOrDefault("x", "fallback"));
    assertEquals("fallback", latch.tryGetOrDefault("x", "fallback"));
  }

  /** A subclass may expose {@code unlatch}, for a policy that retries. */
  private static final class Resumable extends Latch<String, String, RuntimeException> {
    int calls;

    @Override
    protected String get(String target) {
      calls++;
      latch();
      return "called";
    }

    void resume() {
      unlatch();
    }
  }

  @Test
  void unlatchResumesTheOperation() {
    Resumable latch = new Resumable();

    assertEquals("called", latch.tryGetOrNull("x"));
    assertNull(latch.tryGetOrNull("x"));
    assertEquals(1, latch.calls);

    latch.resume();

    assertFalse(latch.isLatched());
    assertEquals("called", latch.tryGetOrNull("x"));
    assertEquals(2, latch.calls);
  }

  @Test
  void checkedExceptionsPropagate() {
    Latch<String, String, SQLException> latch =
        new Latch<String, String, SQLException>() {
          @Override
          protected String get(String target) throws SQLException {
            throw new SQLException("boom");
          }
        };

    assertThrows(SQLException.class, () -> latch.tryGetOrNull("x"));
    assertFalse(latch.isLatched());
  }

  /** What a call site writes: {@code get} delegating to {@code handleNoSuchField}. */
  private static final class Handling extends Latch<String, String, RuntimeException> {
    final AtomicInteger calls = new AtomicInteger();
    Function<String, String> read;

    @Override
    protected String get(String target) {
      return handleNoSuchField(
          target,
          t -> {
            calls.incrementAndGet();
            return read.apply(t);
          });
    }
  }

  @Test
  void handleNoSuchFieldReturnsTheResultWithoutLatching() {
    Handling latch = new Handling();
    latch.read = t -> "value";

    assertEquals("value", latch.tryGetOrNull("x"));
    assertFalse(latch.isLatched());
  }

  @Test
  void handleNoSuchFieldLatchesAndRethrowsTheFirstFailure() {
    Handling latch = new Handling();
    NoSuchFieldError failure = new NoSuchFieldError("_interner");
    latch.read =
        t -> {
          throw failure;
        };

    NoSuchFieldError thrown = assertThrows(NoSuchFieldError.class, () -> latch.tryGetOrNull("x"));

    assertSame(failure, thrown);
    assertTrue(latch.isLatched());
    assertNull(latch.tryGetOrNull("x"));
    assertEquals(1, latch.calls.get(), "later calls should be skipped");
  }

  @Test
  void handleNoSuchFieldDoesNotLatchOnOtherFailures() {
    Handling latch = new Handling();
    latch.read =
        t -> {
          throw new IllegalStateException("boom");
        };

    assertThrows(IllegalStateException.class, () -> latch.tryGetOrNull("x"));
    assertThrows(IllegalStateException.class, () -> latch.tryGetOrNull("x"));

    assertFalse(latch.isLatched());
    assertEquals(2, latch.calls.get());
  }
}
