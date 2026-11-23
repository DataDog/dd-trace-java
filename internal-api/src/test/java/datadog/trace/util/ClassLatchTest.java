package datadog.trace.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
    // no message: String has no abstract getClientInfo, so the error is not attributed to it
    assertFalse(ClassLatch.isNamedIn(new AbstractMethodError(), String.class, "getClientInfo"));
    assertFalse(
        ClassLatch.isNamedIn(
            new AbstractMethodError("something else"), String.class, "getClientInfo"));
  }

  /** Stands in for a class that never implemented an interface method it declares. */
  abstract static class Unimplemented {
    public abstract String m();

    public String implemented() {
      return "ok";
    }
  }

  /** Stands in for a wrapper that implements the method by delegating it. */
  static class Delegating extends Unimplemented {
    @Override
    public String m() {
      return "delegated";
    }
  }

  @Test
  void attributesAnErrorWithoutAMessageByTheClassItself() {
    // JDK 8 gives no message once the call site has seen a class that implements the method
    AbstractMethodError noMessage = new AbstractMethodError();
    assertTrue(ClassLatch.isNamedIn(noMessage, Unimplemented.class, "m"));
    // the class implements this method, so an error from it must come from elsewhere
    assertFalse(ClassLatch.isNamedIn(noMessage, Unimplemented.class, "implemented"));
    // a delegating wrapper is not blamed for its delegate's error
    assertFalse(ClassLatch.isNamedIn(noMessage, Delegating.class, "m"));
    assertFalse(ClassLatch.isNamedIn(noMessage, Unimplemented.class, "missing"));
  }

  /** Stands in for a type that is missing from the class path at run time. */
  public static class Missing {}

  /** A class with a public signature that references {@link Missing}. */
  public static class ReferencesMissing {
    public Missing m() {
      return null;
    }
  }

  @Test
  void anUnresolvableSignatureMeansCannotTellNotAnEscapingError() throws Exception {
    Class<?> type = withoutMissing().loadClass(ReferencesMissing.class.getName());
    // getMethods() resolves every public signature, so the missing return type fails it
    assertThrows(NoClassDefFoundError.class, type::getMethods);

    assertFalse(ClassLatch.isNamedIn(new AbstractMethodError(), type, "m"));
    // the answer is cached, so a second failure neither rescans nor throws
    assertFalse(ClassLatch.isNamedIn(new AbstractMethodError(), type, "m"));
  }

  /** Defines {@link ReferencesMissing} itself, and refuses to load {@link Missing}. */
  private static ClassLoader withoutMissing() {
    return new ClassLoader(ClassLatchTest.class.getClassLoader()) {
      @Override
      protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (name.equals(Missing.class.getName())) {
          throw new ClassNotFoundException(name);
        }
        if (!name.equals(ReferencesMissing.class.getName())) {
          return super.loadClass(name, resolve);
        }
        synchronized (getClassLoadingLock(name)) {
          Class<?> loaded = findLoadedClass(name);
          if (loaded == null) {
            byte[] bytes = classBytes(name);
            loaded = defineClass(name, bytes, 0, bytes.length);
          }
          return loaded;
        }
      }
    };
  }

  private static byte[] classBytes(String name) throws ClassNotFoundException {
    try (InputStream in =
        ClassLatchTest.class
            .getClassLoader()
            .getResourceAsStream(name.replace('.', '/') + ".class")) {
      if (in == null) {
        throw new ClassNotFoundException(name);
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      for (int read; (read = in.read(buffer)) != -1; ) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new ClassNotFoundException(name, e);
    }
  }

  @Test
  void latchIfNamedLatchesOnAnErrorWithoutAMessage() {
    final boolean[] result = new boolean[1];
    ClassLatch<Object, String, RuntimeException> latch =
        new ClassLatch<Object, String, RuntimeException>() {
          @Override
          protected Class<?> keyOf(Object target) {
            return Unimplemented.class;
          }

          @Override
          protected String apply(Object target) {
            result[0] = latchIfNamed(target, "m", new AbstractMethodError());
            return "unnamed";
          }
        };
    latch.tryApply("x");
    assertTrue(result[0]);
    assertTrue(latch.isLatched("x"));
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
