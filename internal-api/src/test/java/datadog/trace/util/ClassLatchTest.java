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
    protected String apply(Object target) {
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

    assertEquals("failed", latch.tryApply("x"));
    assertNull(latch.tryApply("y"));
    assertNull(latch.tryApply("z"));

    assertEquals(1, latch.calls.get());
    assertTrue(latch.isLatched("x"));
  }

  @Test
  void otherClassesAreUnaffected() {
    Counting latch = new Counting();
    latch.tryApply("x");

    assertFalse(latch.isLatched(Integer.valueOf(1)));
    assertEquals("failed", latch.tryApply(Integer.valueOf(1)));
    assertEquals(2, latch.calls.get());
  }

  @Test
  void nullTargetReturnsTheDefaultWithoutCalling() {
    Counting latch = new Counting();

    assertNull(latch.tryApply(null));
    assertFalse(latch.isLatched(null));
    assertEquals(0, latch.calls.get());
  }

  @Test
  void tryApplyOrDefaultReturnsTheResultWhenThereIsOne() {
    ClassLatch<Object, Boolean, RuntimeException> latch =
        new ClassLatch<Object, Boolean, RuntimeException>() {
          @Override
          protected Boolean apply(Object target) {
            return false;
          }
        };

    // a real false must not be replaced by the fallback
    assertEquals(false, latch.tryApplyOrDefault("x", Boolean.TRUE));
  }

  @Test
  void tryApplyOrDefaultReturnsTheFallbackWhenSkippedOrNull() {
    Counting latch = new Counting();

    // the first call latches and yields a value; later calls are skipped
    assertEquals("failed", latch.tryApplyOrDefault("x", "fallback"));
    assertEquals("fallback", latch.tryApplyOrDefault("x", "fallback"));
    assertEquals("fallback", latch.tryApplyOrDefault(null, "fallback"));
  }

  @Test
  void aCallThatYieldsNothingAndASkippedCallAgree() {
    ClassLatch<Object, String, RuntimeException> latch =
        new ClassLatch<Object, String, RuntimeException>() {
          @Override
          protected String apply(Object target) {
            latch(target);
            return null;
          }
        };

    assertEquals("fallback", latch.tryApplyOrDefault("x", "fallback"));
    assertEquals("fallback", latch.tryApplyOrDefault("x", "fallback"));
  }

  @Test
  void aNullTargetYieldsNullNotTheFallback() {
    ClassLatch<Object, String, RuntimeException> latch =
        new ClassLatch<Object, String, RuntimeException>() {
          @Override
          protected String apply(Object target) {
            return "called";
          }

          @Override
          protected String fallback(Object target) {
            return "fallback";
          }
        };

    assertNull(latch.tryApply(null));
    assertEquals("default", latch.tryApplyOrDefault(null, "default"));
  }

  @Test
  void tryApplyOrDefaultPrefersTheFallbackOverTheDefault() {
    ClassLatch<Object, String, RuntimeException> latch =
        new ClassLatch<Object, String, RuntimeException>() {
          @Override
          protected String apply(Object target) {
            latch(target);
            return fallback(target);
          }

          @Override
          protected String fallback(Object target) {
            return "fallback";
          }
        };

    assertEquals("fallback", latch.tryApplyOrDefault("x", "default"));
    assertEquals("fallback", latch.tryApplyOrDefault("x", "default"));
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
          protected String apply(Wrapper target) {
            latch(target);
            return "called";
          }

          @Override
          protected Class<?> keyOf(Wrapper target) {
            return target.delegate.getClass();
          }
        };

    assertEquals("called", latch.tryApply(new Wrapper("x")));

    assertTrue(latch.isLatched(new Wrapper("another string")));
    assertFalse(latch.isLatched(new Wrapper(Integer.valueOf(1))));
    assertEquals("called", latch.tryApply(new Wrapper(Integer.valueOf(1))));
  }

  /** A subclass may expose {@code unlatch}, for a policy that retries. */
  private static final class Resumable extends ClassLatch<Object, String, RuntimeException> {
    @Override
    protected String apply(Object target) {
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
    latch.tryApply("x");
    latch.tryApply(Integer.valueOf(1));
    assertTrue(latch.isLatched("x"));
    assertTrue(latch.isLatched(Integer.valueOf(1)));

    latch.resume("x");

    assertFalse(latch.isLatched("x"));
    assertTrue(latch.isLatched(Integer.valueOf(1)));
    assertEquals("called", latch.tryApply("x"));
  }

  @Test
  void latchIfNamedLatchesOnlyWhenTheErrorNamesTheKey() {
    final boolean[] result = new boolean[1];
    ClassLatch<Object, String, RuntimeException> latch =
        new ClassLatch<Object, String, RuntimeException>() {
          @Override
          protected String apply(Object target) {
            result[0] = latchIfNamed(target, "m", receiverError(target.getClass()));
            return "named";
          }
        };
    latch.tryApply("x");
    assertTrue(result[0]);
    assertTrue(latch.isLatched("x"));

    ClassLatch<Object, String, RuntimeException> other =
        new ClassLatch<Object, String, RuntimeException>() {
          @Override
          protected String apply(Object target) {
            result[0] = latchIfNamed(target, "m", receiverError(Integer.class));
            return "other";
          }
        };
    other.tryApply("x");
    assertFalse(result[0]);
    assertFalse(other.isLatched("x"));
  }

  @Test
  void latchIfNamedDoesNotLatchWhenTheErrorNamesTheKeyButADifferentMethod() {
    // the receiver's own implementation of "m" can call some other method internally; an
    // AbstractMethodError raised by that other method still names the receiver class, but it is
    // not evidence that "m" itself is missing
    final boolean[] result = new boolean[1];
    ClassLatch<Object, String, RuntimeException> latch =
        new ClassLatch<Object, String, RuntimeException>() {
          @Override
          protected String apply(Object target) {
            result[0] =
                latchIfNamed(
                    target,
                    "m",
                    new AbstractMethodError(
                        "Receiver class "
                            + target.getClass().getName()
                            + " does not define or inherit an implementation of the resolved"
                            + " method 'abstract java.lang.String other()' of interface I."));
            return "named";
          }
        };
    latch.tryApply("x");
    assertFalse(result[0]);
    assertFalse(latch.isLatched("x"));
  }

  @Test
  void attributesTheHotSpotMessageFormats() {
    assertTrue(ClassLatch.isNamedIn(receiverError(String.class), String.class, "m"));
    // the message names the receiver class, but blames a different method than "m"
    assertFalse(ClassLatch.isNamedIn(receiverError(String.class), String.class, "other"));
    // "java.lang.String" is a prefix of "java.lang.StringBuilder", but not the same class
    assertFalse(ClassLatch.isNamedIn(receiverError(String.class), StringBuilder.class, "m"));
    assertFalse(
        ClassLatch.isNamedIn(
            new AbstractMethodError("Receiver class " + String.class.getName()),
            String.class,
            "m"));

    // JDK 8 reports "<receiver class>.<method><descriptor>"
    String name = String.class.getName();
    assertTrue(
        ClassLatch.isNamedIn(
            new AbstractMethodError(name + ".getClientInfo()Ljava/util/Properties;"),
            String.class,
            "getClientInfo"));
    // the message names the receiver class, but blames a different method
    assertFalse(
        ClassLatch.isNamedIn(
            new AbstractMethodError(name + ".otherMethod()Ljava/util/Properties;"),
            String.class,
            "getClientInfo"));
    // a different class whose name merely starts with this one
    assertFalse(
        ClassLatch.isNamedIn(
            new AbstractMethodError(name + "Builder.getClientInfo()Ljava/util/Properties;"),
            String.class,
            "getClientInfo"));
    // a class in a package named like this class
    assertFalse(
        ClassLatch.isNamedIn(
            new AbstractMethodError(name + ".Inner.getClientInfo()Ljava/util/Properties;"),
            String.class,
            "getClientInfo"));
    assertFalse(ClassLatch.isNamedIn(new AbstractMethodError(name), String.class, "getClientInfo"));
    assertFalse(
        ClassLatch.isNamedIn(new AbstractMethodError(name + "."), String.class, "getClientInfo"));
    assertFalse(ClassLatch.isNamedIn(new AbstractMethodError(), String.class, "getClientInfo"));
    assertFalse(
        ClassLatch.isNamedIn(
            new AbstractMethodError("something else"), String.class, "getClientInfo"));
  }

  @Test
  void checkedExceptionsPropagateWithoutLatching() {
    ClassLatch<Object, String, SQLException> latch =
        new ClassLatch<Object, String, SQLException>() {
          @Override
          protected String apply(Object target) throws SQLException {
            throw new SQLException("boom");
          }
        };

    assertThrows(SQLException.class, () -> latch.tryApply("x"));
    assertFalse(latch.isLatched("x"));
  }
}
