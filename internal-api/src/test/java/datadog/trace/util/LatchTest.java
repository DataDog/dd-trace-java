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
    protected Boolean apply(String target) {
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

    assertEquals(false, latch.tryApplyOrNull("x"));
    assertEquals(false, latch.tryApplyOrNull("x"));

    assertEquals(2, latch.calls.get());
    assertFalse(latch.isLatched());
  }

  @Test
  void rethrowsTheFirstFailureThenSkipsTheOperation() {
    FieldLatch latch = new FieldLatch();

    assertThrows(NoSuchFieldError.class, () -> latch.tryApplyOrNull("x"));
    assertTrue(latch.isLatched());

    assertNull(latch.tryApplyOrNull("x"));
    assertNull(latch.tryApplyOrNull("y"));
    assertEquals(1, latch.calls.get(), "later calls should be skipped");
  }

  @Test
  void onceLatchedEveryCallYieldsTheFallback() {
    AtomicInteger calls = new AtomicInteger();
    Latch<String, String, RuntimeException> latch =
        new Latch<String, String, RuntimeException>() {
          @Override
          protected String apply(String target) {
            calls.incrementAndGet();
            return handleNoSuchField(
                target,
                t -> {
                  throw new NoSuchFieldError("f");
                });
          }

          @Override
          protected String fallback(String target) {
            return "fallback:" + target;
          }
        };

    // the first failure is still rethrown, not replaced by the fallback
    assertThrows(NoSuchFieldError.class, () -> latch.tryApplyOrNull("x"));

    assertEquals("fallback:x", latch.tryApplyOrNull("x"));
    assertEquals("fallback:y", latch.tryApplyOrDefault("y", "default"));
    assertEquals(1, calls.get(), "later calls should be skipped");
  }

  @Test
  void aRealNullIsNotReplacedByTheFallback() {
    Latch<String, String, RuntimeException> latch =
        new Latch<String, String, RuntimeException>() {
          @Override
          protected String apply(String target) {
            return null;
          }

          @Override
          protected String fallback(String target) {
            return "fallback";
          }
        };

    assertNull(latch.tryApplyOrNull("x"));
    assertFalse(latch.isLatched());
  }

  @Test
  void tryApplyOrDefaultReturnsTheResultWhenThereIsOne() {
    FieldLatch latch = new FieldLatch();
    latch.fieldPresent = true;

    // a real false must not be replaced by the fallback
    assertEquals(false, latch.tryApplyOrDefault("x", Boolean.TRUE));
  }

  @Test
  void tryApplyOrDefaultReturnsTheFallbackOnceLatched() {
    FieldLatch latch = new FieldLatch();
    assertThrows(NoSuchFieldError.class, () -> latch.tryApplyOrDefault("x", Boolean.TRUE));

    assertEquals(true, latch.tryApplyOrDefault("x", Boolean.TRUE));
    assertEquals(true, latch.tryApplyOrDefault("y", Boolean.TRUE));
    assertEquals(1, latch.calls.get(), "later calls should be skipped");
  }

  @Test
  void aCallThatYieldsNothingAndASkippedCallAgree() {
    // the first call latches and returns null; the next is skipped. Both must give the fallback.
    Latch<String, String, RuntimeException> latch =
        new Latch<String, String, RuntimeException>() {
          @Override
          protected String apply(String target) {
            latch();
            return null;
          }
        };

    assertEquals("fallback", latch.tryApplyOrDefault("x", "fallback"));
    assertEquals("fallback", latch.tryApplyOrDefault("x", "fallback"));
  }

  /** A subclass may expose {@code unlatch}, for a policy that retries. */
  private static final class Resumable extends Latch<String, String, RuntimeException> {
    int calls;

    @Override
    protected String apply(String target) {
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

    assertEquals("called", latch.tryApplyOrNull("x"));
    assertNull(latch.tryApplyOrNull("x"));
    assertEquals(1, latch.calls);

    latch.resume();

    assertFalse(latch.isLatched());
    assertEquals("called", latch.tryApplyOrNull("x"));
    assertEquals(2, latch.calls);
  }

  @Test
  void checkedExceptionsPropagate() {
    Latch<String, String, SQLException> latch =
        new Latch<String, String, SQLException>() {
          @Override
          protected String apply(String target) throws SQLException {
            throw new SQLException("boom");
          }
        };

    assertThrows(SQLException.class, () -> latch.tryApplyOrNull("x"));
    assertFalse(latch.isLatched());
  }

  /** What a call site writes: {@code apply} delegating to {@code handleNoSuchField}. */
  private static final class Handling extends Latch<String, String, RuntimeException> {
    final AtomicInteger calls = new AtomicInteger();
    Function<String, String> read;

    @Override
    protected String apply(String target) {
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

    assertEquals("value", latch.tryApplyOrNull("x"));
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

    NoSuchFieldError thrown = assertThrows(NoSuchFieldError.class, () -> latch.tryApplyOrNull("x"));

    assertSame(failure, thrown);
    assertTrue(latch.isLatched());
    assertNull(latch.tryApplyOrNull("x"));
    assertEquals(1, latch.calls.get(), "later calls should be skipped");
  }

  @Test
  void handleNoSuchFieldDoesNotLatchOnOtherFailures() {
    Handling latch = new Handling();
    latch.read =
        t -> {
          throw new IllegalStateException("boom");
        };

    assertThrows(IllegalStateException.class, () -> latch.tryApplyOrNull("x"));
    assertThrows(IllegalStateException.class, () -> latch.tryApplyOrNull("x"));

    assertFalse(latch.isLatched());
    assertEquals(2, latch.calls.get());
  }
}
