package datadog.trace.util;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HandleAbstractMethodTest {

  /** What a call site writes: {@code apply} delegating to {@code handleAbstractMethod}. */
  private abstract static class Handling<T, R, E extends Exception> extends ClassLatch<T, R, E> {
    protected abstract R invoke(T target) throws E;

    @Override
    protected final R apply(T target) throws E {
      return handleAbstractMethod(target, this::invoke);
    }
  }

  private static AbstractMethodError receiverError(Class<?> type) {
    return new AbstractMethodError(
        "Receiver class "
            + type.getName()
            + " does not define or inherit an implementation of the resolved method 'abstract"
            + " java.lang.String m()' of interface I.");
  }

  private static final class Throwing extends Handling<Object, String, Exception> {
    final AtomicInteger calls = new AtomicInteger();
    final Throwable failure;

    Throwing(Throwable failure) {
      this.failure = failure;
    }

    @Override
    protected String invoke(Object target) throws Exception {
      calls.incrementAndGet();
      if (failure instanceof AbstractMethodError) {
        // name the class of whatever was called, as the JVM does for the receiver
        throw receiverError(target.getClass());
      }
      if (failure instanceof Exception) {
        throw (Exception) failure;
      }
      throw (Error) failure;
    }
  }

  @Test
  void returnsTheResultAndLatchesNothing() throws Exception {
    Handling<Object, String, RuntimeException> latch =
        new Handling<Object, String, RuntimeException>() {
          @Override
          protected String invoke(Object target) {
            return "ok";
          }
        };

    assertEquals("ok", latch.tryApplyOrNull("x"));
    assertFalse(latch.isLatched("x"));
  }

  @Test
  void latchesTheReceiverClassAndStopsCalling() throws Exception {
    Throwing latch = new Throwing(new AbstractMethodError());

    assertNull(latch.tryApplyOrNull("x"));
    assertNull(latch.tryApplyOrNull("y"));
    assertNull(latch.tryApplyOrNull("z"));

    assertEquals(1, latch.calls.get());
    assertTrue(latch.isLatched("x"));
  }

  @Test
  void otherClassesAreUnaffectedByALatch() throws Exception {
    Throwing latch = new Throwing(new AbstractMethodError());
    latch.tryApplyOrNull("x");

    assertFalse(latch.isLatched(Integer.valueOf(1)));
    assertNull(latch.tryApplyOrNull(Integer.valueOf(1)));
    assertEquals(2, latch.calls.get());
  }

  @Test
  void doesNotLatchWhenTheErrorNamesAnotherClass() throws Exception {
    // a wrapper whose delegate lacks the method: the error names the delegate, not the wrapper
    AtomicInteger calls = new AtomicInteger();
    Handling<Object, String, RuntimeException> latch =
        new Handling<Object, String, RuntimeException>() {
          @Override
          protected String invoke(Object target) {
            calls.incrementAndGet();
            throw receiverError(Integer.class);
          }
        };

    assertNull(latch.tryApplyOrNull("x"));
    assertNull(latch.tryApplyOrNull("x"));

    assertEquals(2, calls.get());
    assertFalse(latch.isLatched("x"));
  }

  @Test
  void doesNotLatchWhenTheMessageCannotBeAttributed() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    for (AbstractMethodError error :
        new AbstractMethodError[] {
          new AbstractMethodError(), new AbstractMethodError("something else entirely")
        }) {
      Handling<Object, String, RuntimeException> latch =
          new Handling<Object, String, RuntimeException>() {
            @Override
            protected String invoke(Object target) {
              calls.incrementAndGet();
              throw error;
            }
          };

      assertNull(latch.tryApplyOrNull("x"));
      assertFalse(latch.isLatched("x"));
    }
    assertEquals(2, calls.get());
  }

  @Test
  void unsupportedOperationYieldsTheDefaultOnEveryCallAndIsNeverLatched() throws Exception {
    Throwing latch = new Throwing(new UnsupportedOperationException());

    assertNull(latch.tryApplyOrNull("x"));
    assertNull(latch.tryApplyOrNull("x"));

    assertEquals(2, latch.calls.get());
    assertFalse(latch.isLatched("x"));
  }

  @Test
  void checkedExceptionsPropagateAndDoNotLatch() {
    SQLException failure = new SQLException("boom");
    Throwing latch = new Throwing(failure);

    SQLException thrown = assertThrows(SQLException.class, () -> latch.tryApplyOrNull("x"));

    assertSame(failure, thrown);
    assertFalse(latch.isLatched("x"));
  }

  @Test
  void otherUncheckedExceptionsPropagateAndDoNotLatch() {
    Throwing latch = new Throwing(new IllegalStateException());

    assertThrows(IllegalStateException.class, () -> latch.tryApplyOrNull("x"));
    assertFalse(latch.isLatched("x"));
  }

  /**
   * Pins the HotSpot message format that attribution depends on, using a real error: a class built
   * against an old interface, called through code built against a newer one.
   */
  @Test
  void latchesARealAbstractMethodError(@TempDir Path dir) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assumeTrue(compiler != null, "needs a JDK");
    String vm = System.getProperty("java.vm.name", "");
    assumeTrue(vm.contains("HotSpot") || vm.contains("OpenJDK"), "message format is HotSpot's");

    Path oldDir = Files.createDirectory(dir.resolve("old"));
    Path newDir = Files.createDirectory(dir.resolve("new"));
    compile(compiler, oldDir, null, "I", "public interface I { String a(); }");
    compile(
        compiler,
        oldDir,
        oldDir,
        "Impl",
        "public class Impl implements I { public String a() { return \"a\"; } }");
    compile(compiler, newDir, null, "I", "public interface I { String a(); String b(); }");
    compile(
        compiler,
        newDir,
        newDir,
        "Caller",
        "public class Caller { public static String call(I i) { return i.b(); } }");

    try (URLClassLoader loader =
        new URLClassLoader(
            new URL[] {newDir.toUri().toURL(), oldDir.toUri().toURL()},
            HandleAbstractMethodTest.class.getClassLoader())) {
      Class<?> iface = loader.loadClass("I");
      Object impl = loader.loadClass("Impl").getDeclaredConstructor().newInstance();
      Method call = loader.loadClass("Caller").getMethod("call", iface);

      AtomicInteger calls = new AtomicInteger();
      Handling<Object, Object, Exception> latch =
          new Handling<Object, Object, Exception>() {
            @Override
            protected Object invoke(Object target) throws Exception {
              calls.incrementAndGet();
              try {
                return call.invoke(null, target);
              } catch (InvocationTargetException e) {
                if (e.getCause() instanceof AbstractMethodError) {
                  throw (AbstractMethodError) e.getCause();
                }
                throw e;
              }
            }
          };

      assertNull(latch.tryApplyOrNull(impl));
      assertNull(latch.tryApplyOrNull(impl));

      assertEquals(1, calls.get(), "second call should be skipped");
      assertTrue(latch.isLatched(impl));
    }
  }

  private static void compile(
      JavaCompiler compiler, Path out, Path classpath, String name, String source)
      throws IOException {
    Path file = out.resolve(name + ".java");
    Files.write(file, singletonList(source), StandardCharsets.UTF_8);
    int result =
        classpath == null
            ? compiler.run(null, null, null, "-d", out.toString(), file.toString())
            : compiler.run(
                null,
                null,
                null,
                "-cp",
                classpath.toString() + File.pathSeparator,
                "-d",
                out.toString(),
                file.toString());
    assertEquals(0, result, "compiling " + name);
  }
}
