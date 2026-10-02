package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HandleNoSuchMethodTest {

  /** What a call site writes: {@code apply} delegating to {@code handleNoSuchMethod}. */
  private static final class Throwing extends ClassLatch<Object, String, Exception> {
    final AtomicInteger calls = new AtomicInteger();
    final Throwable failure;

    Throwing(Throwable failure) {
      this.failure = failure;
    }

    @Override
    protected String apply(Object target) throws Exception {
      return handleNoSuchMethod(
          target,
          t -> {
            calls.incrementAndGet();
            if (failure instanceof Exception) {
              throw (Exception) failure;
            }
            throw (Error) failure;
          });
    }
  }

  @Test
  void returnsTheResultAndLatchesNothing() throws Exception {
    ClassLatch<Object, String, RuntimeException> latch =
        new ClassLatch<Object, String, RuntimeException>() {
          @Override
          protected String apply(Object target) {
            return handleNoSuchMethod(target, t -> "ok");
          }
        };

    assertEquals("ok", latch.tryApply("x"));
    assertFalse(latch.isLatched("x"));
  }

  @Test
  void noSuchMethodYieldsTheFallbackNowAndOnceLatched() {
    AtomicInteger calls = new AtomicInteger();
    ClassLatch<Object, String, RuntimeException> latch =
        new ClassLatch<Object, String, RuntimeException>() {
          @Override
          protected String apply(Object target) {
            return handleNoSuchMethod(
                target,
                t -> {
                  calls.incrementAndGet();
                  throw new NoSuchMethodError();
                });
          }

          @Override
          protected String fallback(Object target) {
            return "fallback";
          }
        };

    assertEquals("fallback", latch.tryApply("x"));
    assertEquals("fallback", latch.tryApply("x"));
    assertEquals(1, calls.get());
  }

  @Test
  void noSuchMethodYieldsNullAndLatchesTheTargetsClass() throws Exception {
    Throwing latch = new Throwing(new NoSuchMethodError("I.b()Ljava/lang/String;"));

    assertNull(latch.tryApply("x"));
    assertNull(latch.tryApply("y"));

    assertEquals(1, latch.calls.get(), "later calls should be skipped");
    assertTrue(latch.isLatched("x"));
    // the message names the declared type, not the receiver: another class gets its own attempt
    assertFalse(latch.isLatched(Integer.valueOf(1)));
  }

  @Test
  void doesNotHandleAbstractMethodErrorOrUnsupportedOperation() {
    Throwing abstractMethod = new Throwing(new AbstractMethodError("Impl.b()V"));
    assertThrows(AbstractMethodError.class, () -> abstractMethod.tryApply("x"));
    assertFalse(abstractMethod.isLatched("x"));

    Throwing unsupported = new Throwing(new UnsupportedOperationException());
    assertThrows(UnsupportedOperationException.class, () -> unsupported.tryApply("x"));
    assertFalse(unsupported.isLatched("x"));
  }

  @Test
  void otherFailuresPropagateWithoutLatching() {
    Throwing checked = new Throwing(new SQLException("boom"));
    assertThrows(SQLException.class, () -> checked.tryApply("x"));
    assertFalse(checked.isLatched("x"));
  }
}
