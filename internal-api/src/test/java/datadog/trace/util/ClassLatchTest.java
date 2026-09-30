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
    protected String get(Object target) {
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

    assertEquals("failed", latch.getOrDefault("x"));
    assertNull(latch.getOrDefault("y"));
    assertNull(latch.getOrDefault("z"));

    assertEquals(1, latch.calls.get());
    assertTrue(latch.isLatched("x"));
  }

  @Test
  void otherClassesAreUnaffected() {
    Counting latch = new Counting();
    latch.getOrDefault("x");

    assertFalse(latch.isLatched(Integer.valueOf(1)));
    assertEquals("failed", latch.getOrDefault(Integer.valueOf(1)));
    assertEquals(2, latch.calls.get());
  }

  @Test
  void nullTargetReturnsTheDefaultWithoutCalling() {
    Counting latch = new Counting();

    assertNull(latch.getOrDefault(null));
    assertFalse(latch.isLatched(null));
    assertEquals(0, latch.calls.get());
  }

  @Test
  void defaultValueIsUsedWhenSkipped() {
    ClassLatch<Object, String, RuntimeException> latch =
        new ClassLatch<Object, String, RuntimeException>() {
          @Override
          protected String get(Object target) {
            latch(target);
            return "first";
          }

          @Override
          protected String defaultValue(Object target) {
            return "default";
          }
        };

    assertEquals("first", latch.getOrDefault("x"));
    assertEquals("default", latch.getOrDefault("x"));
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
          protected String get(Wrapper target) {
            latch(target);
            return "called";
          }

          @Override
          protected Class<?> keyOf(Wrapper target) {
            return target.delegate.getClass();
          }
        };

    assertEquals("called", latch.getOrDefault(new Wrapper("x")));

    assertTrue(latch.isLatched(new Wrapper("another string")));
    assertFalse(latch.isLatched(new Wrapper(Integer.valueOf(1))));
    assertEquals("called", latch.getOrDefault(new Wrapper(Integer.valueOf(1))));
  }

  @Test
  void unlatchResumesForThatKeyOnly() {
    ClassLatch<Object, String, RuntimeException> resuming =
        new ClassLatch<Object, String, RuntimeException>() {
          @Override
          protected String get(Object target) {
            latch(target);
            return "called";
          }

          @Override
          protected String defaultValue(Object target) {
            unlatch(target);
            return "skipped";
          }
        };
    resuming.getOrDefault("x");
    resuming.getOrDefault(Integer.valueOf(1));

    assertEquals("skipped", resuming.getOrDefault("x"));
    assertFalse(resuming.isLatched("x"));
    assertTrue(resuming.isLatched(Integer.valueOf(1)));
  }

  @Test
  void latchIfNamedLatchesOnlyWhenTheErrorNamesTheKey() {
    final boolean[] result = new boolean[1];
    ClassLatch<Object, String, RuntimeException> latch =
        new ClassLatch<Object, String, RuntimeException>() {
          @Override
          protected String get(Object target) {
            result[0] = latchIfNamed(target, receiverError(target.getClass()));
            return "named";
          }
        };
    latch.getOrDefault("x");
    assertTrue(result[0]);
    assertTrue(latch.isLatched("x"));

    ClassLatch<Object, String, RuntimeException> other =
        new ClassLatch<Object, String, RuntimeException>() {
          @Override
          protected String get(Object target) {
            result[0] = latchIfNamed(target, receiverError(Integer.class));
            return "other";
          }
        };
    other.getOrDefault("x");
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
          protected String get(Object target) throws SQLException {
            throw new SQLException("boom");
          }
        };

    assertThrows(SQLException.class, () -> latch.getOrDefault("x"));
    assertFalse(latch.isLatched("x"));
  }
}
