package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ClassLatchTest {

  private static AbstractMethodError receiverError(Class<?> type) {
    return new AbstractMethodError(
        "Receiver class "
            + type.getName()
            + " does not define or inherit an implementation of the resolved method 'abstract"
            + " java.lang.String m()' of interface I.");
  }

  /** Latches the target's class unconditionally on any IllegalStateException. */
  private static class Counting extends ClassLatch<Object, String, RuntimeException> {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    protected String handle(Object target) {
      calls.incrementAndGet();
      try {
        throw new IllegalStateException();
      } catch (IllegalStateException e) {
        latch(target);
        return "failed";
      }
    }
  }

  @Test
  void latchesTheTargetsClassAndSkipsLaterCalls() {
    Counting latch = new Counting();

    assertEquals("failed", latch.tryGetOrNull("x"));
    assertNull(latch.tryGetOrNull("y"));
    assertNull(latch.tryGetOrNull("z"));

    assertEquals(1, latch.calls.get());
    assertTrue(latch.isLatched("x"));
  }

  @Test
  void otherClassesAreUnaffected() {
    Counting latch = new Counting();
    latch.tryGetOrNull("x");

    assertFalse(latch.isLatched(Integer.valueOf(1)));
    assertEquals("failed", latch.tryGetOrNull(Integer.valueOf(1)));
    assertEquals(2, latch.calls.get());
  }

  @Test
  void nullTargetReturnsTheDefaultWithoutCalling() {
    Counting latch = new Counting();

    assertNull(latch.tryGetOrNull(null));
    assertFalse(latch.isLatched(null));
    assertEquals(0, latch.calls.get());
  }

  @Test
  void tryGetOrDefaultReturnsTheResultWhenThereIsOne() {
    ClassLatch<Object, Boolean, RuntimeException> latch =
        new ClassLatch<Object, Boolean, RuntimeException>() {
          @Override
          protected Boolean handle(Object target) {
            return false;
          }
        };

    // a real false must not be replaced by the fallback
    assertEquals(false, latch.tryGetOrDefault("x", Boolean.TRUE));
  }

  @Test
  void tryGetOrDefaultReturnsTheFallbackWhenSkippedOrNull() {
    Counting latch = new Counting();

    // the first call latches and yields a value; later calls are skipped
    assertEquals("failed", latch.tryGetOrDefault("x", "fallback"));
    assertEquals("fallback", latch.tryGetOrDefault("x", "fallback"));
    assertEquals("fallback", latch.tryGetOrDefault(null, "fallback"));
  }

  @Test
  void aCallThatYieldsNothingAndASkippedCallAgree() {
    ClassLatch<Object, String, RuntimeException> latch =
        new ClassLatch<Object, String, RuntimeException>() {
          @Override
          protected String handle(Object target) {
            latch(target);
            return null;
          }
        };

    assertEquals("fallback", latch.tryGetOrDefault("x", "fallback"));
    assertEquals("fallback", latch.tryGetOrDefault("x", "fallback"));
  }

  @Test
  void keyOfChoosesTheClassToLatch() {
    // a wrapper whose contents differ: latch on what it holds, never on the wrapper itself
    final class Wrapper {
      final Object delegate;

      Wrapper(Object delegate) {
        this.delegate = delegate;
      }
    }
    ClassLatch<Wrapper, String, RuntimeException> latch =
        new ClassLatch<Wrapper, String, RuntimeException>() {
          @Override
          protected String handle(Wrapper target) {
            latch(target);
            return "called";
          }

          @Override
          protected Class<?> keyOf(Wrapper target) {
            return target.delegate.getClass();
          }
        };

    assertEquals("called", latch.tryGetOrNull(new Wrapper("x")));

    assertTrue(latch.isLatched(new Wrapper("another string")));
    assertFalse(latch.isLatched(new Wrapper(Integer.valueOf(1))));
    assertEquals("called", latch.tryGetOrNull(new Wrapper(Integer.valueOf(1))));
  }

  /** A subclass may expose {@code unlatch}, for a policy that retries. */
  private static final class Resumable extends ClassLatch<Object, String, RuntimeException> {
    @Override
    protected String handle(Object target) {
      latch(target);
      return "called";
    }

    void resume(Object target) {
      unlatch(target);
    }
  }

  @Test
  void unlatchResumesForThatKeyOnly() {
    Resumable latch = new Resumable();
    latch.tryGetOrNull("x");
    latch.tryGetOrNull(Integer.valueOf(1));
    assertTrue(latch.isLatched("x"));
    assertTrue(latch.isLatched(Integer.valueOf(1)));

    latch.resume("x");

    assertFalse(latch.isLatched("x"));
    assertTrue(latch.isLatched(Integer.valueOf(1)));
    assertEquals("called", latch.tryGetOrNull("x"));
  }

  @Test
  void latchIfNamedLatchesOnlyWhenTheErrorNamesTheKey() {
    final boolean[] result = new boolean[1];
    ClassLatch<Object, String, RuntimeException> latch =
        new ClassLatch<Object, String, RuntimeException>() {
          @Override
          protected String handle(Object target) {
            result[0] = latchIfNamed(target, receiverError(target.getClass()));
            return "named";
          }
        };
    latch.tryGetOrNull("x");
    assertTrue(result[0]);
    assertTrue(latch.isLatched("x"));

    ClassLatch<Object, String, RuntimeException> other =
        new ClassLatch<Object, String, RuntimeException>() {
          @Override
          protected String handle(Object target) {
            result[0] = latchIfNamed(target, receiverError(Integer.class));
            return "other";
          }
        };
    other.tryGetOrNull("x");
    assertFalse(result[0]);
    assertFalse(other.isLatched("x"));
  }

  @Test
  void attributesTheHotSpotMessageFormats() {
    assertTrue(ClassLatch.isNamedIn(receiverError(String.class), String.class));
    // "java.lang.String" is a prefix of "java.lang.StringBuilder", but not the same class
    assertFalse(ClassLatch.isNamedIn(receiverError(String.class), StringBuilder.class));
    assertFalse(
        ClassLatch.isNamedIn(
            new AbstractMethodError("Receiver class " + String.class.getName()), String.class));

    // JDK 8 reports "<receiver class>.<method><descriptor>"
    String name = String.class.getName();
    assertTrue(
        ClassLatch.isNamedIn(
            new AbstractMethodError(name + ".getClientInfo()Ljava/util/Properties;"),
            String.class));
    // a different class whose name merely starts with this one
    assertFalse(
        ClassLatch.isNamedIn(
            new AbstractMethodError(name + "Builder.getClientInfo()Ljava/util/Properties;"),
            String.class));
    // a class in a package named like this class
    assertFalse(
        ClassLatch.isNamedIn(
            new AbstractMethodError(name + ".Inner.getClientInfo()Ljava/util/Properties;"),
            String.class));
    assertFalse(ClassLatch.isNamedIn(new AbstractMethodError(name), String.class));
    assertFalse(ClassLatch.isNamedIn(new AbstractMethodError(name + "."), String.class));
    assertFalse(ClassLatch.isNamedIn(new AbstractMethodError(), String.class));
    assertFalse(ClassLatch.isNamedIn(new AbstractMethodError("something else"), String.class));
  }

  @Test
  void checkedExceptionsPropagateWithoutLatching() {
    ClassLatch<Object, String, SQLException> latch =
        new ClassLatch<Object, String, SQLException>() {
          @Override
          protected String handle(Object target) throws SQLException {
            throw new SQLException("boom");
          }
        };

    assertThrows(SQLException.class, () -> latch.tryGetOrNull("x"));
    assertFalse(latch.isLatched("x"));
  }
}
