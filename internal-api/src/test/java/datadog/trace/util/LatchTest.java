package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LatchTest {

  /** The shape of a missing-field read: rethrow the first failure, then answer a default. */
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

    @Override
    protected Boolean defaultValue(String target) {
      return Boolean.TRUE;
    }
  }

  @Test
  void performsTheOperationUntilLatched() {
    FieldLatch latch = new FieldLatch();
    latch.fieldPresent = true;

    assertEquals(false, latch.getOrDefault("x"));
    assertEquals(false, latch.getOrDefault("x"));

    assertEquals(2, latch.calls.get());
    assertFalse(latch.isLatched());
  }

  @Test
  void rethrowsTheFirstFailureThenAnswersTheDefaultWithoutCalling() {
    FieldLatch latch = new FieldLatch();

    assertThrows(NoSuchFieldError.class, () -> latch.getOrDefault("x"));
    assertTrue(latch.isLatched());

    assertEquals(true, latch.getOrDefault("x"));
    assertEquals(true, latch.getOrDefault("y"));
    assertEquals(1, latch.calls.get(), "later calls should be skipped");
  }

  @Test
  void defaultsToNull() {
    Latch<String, String, RuntimeException> latch =
        new Latch<String, String, RuntimeException>() {
          @Override
          protected String get(String target) {
            latch();
            return "first";
          }
        };

    assertEquals("first", latch.getOrDefault("x"));
    assertNull(latch.getOrDefault("x"));
  }

  @Test
  void unlatchResumesTheOperation() {
    Latch<String, String, RuntimeException> latch =
        new Latch<String, String, RuntimeException>() {
          @Override
          protected String get(String target) {
            latch();
            return "called";
          }

          @Override
          protected String defaultValue(String target) {
            unlatch();
            return "skipped";
          }
        };

    assertEquals("called", latch.getOrDefault("x"));
    assertEquals("skipped", latch.getOrDefault("x"));
    assertFalse(latch.isLatched());
    assertEquals("called", latch.getOrDefault("x"));
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

    assertThrows(SQLException.class, () -> latch.getOrDefault("x"));
    assertFalse(latch.isLatched());
  }
}
