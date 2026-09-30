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

class AbstractMethodGuardTest {

  private static AbstractMethodError receiverError(Class<?> type) {
    return new AbstractMethodError(
        "Receiver class "
            + type.getName()
            + " does not define or inherit an implementation of the resolved method 'abstract"
            + " java.lang.String m()' of interface I.");
  }

  @Test
  void returnsTheResultAndLatchesNothing() {
    AbstractMethodGuard guard = new AbstractMethodGuard();

    assertEquals("ok", guard.invokeOrNull("x", s -> "ok"));
    assertFalse(guard.isLatched(String.class));
  }

  @Test
  void nullTargetReturnsNullWithoutCalling() {
    AbstractMethodGuard guard = new AbstractMethodGuard();
    AtomicInteger calls = new AtomicInteger();

    assertNull(
        guard.invokeOrNull(
            null,
            t -> {
              calls.incrementAndGet();
              return "never";
            }));
    assertEquals(0, calls.get());
  }

  @Test
  void latchesTheReceiverClassAndStopsCalling() {
    AbstractMethodGuard guard = new AbstractMethodGuard();
    AtomicInteger calls = new AtomicInteger();
    AbstractMethodGuard.Call<Object, String, RuntimeException> call =
        t -> {
          calls.incrementAndGet();
          throw receiverError(t.getClass());
        };

    assertNull(guard.invokeOrNull("x", call));
    assertNull(guard.invokeOrNull("y", call));
    assertNull(guard.invokeOrNull("z", call));

    assertEquals(1, calls.get());
    assertTrue(guard.isLatched(String.class));
  }

  @Test
  void otherClassesAreUnaffectedByALatch() {
    AbstractMethodGuard guard = new AbstractMethodGuard();
    guard.invokeOrNull(
        "x",
        t -> {
          throw receiverError(t.getClass());
        });

    assertEquals("called", guard.invokeOrNull(Integer.valueOf(1), t -> "called"));
    assertFalse(guard.isLatched(Integer.class));
  }

  @Test
  void doesNotLatchAWrapperWhoseDelegateIsTheDeficientClass() {
    AbstractMethodGuard guard = new AbstractMethodGuard();
    AtomicInteger calls = new AtomicInteger();
    // the called object is a String, but the error names some other (delegate) class
    AbstractMethodGuard.Call<Object, String, RuntimeException> call =
        t -> {
          calls.incrementAndGet();
          throw receiverError(Integer.class);
        };

    assertNull(guard.invokeOrNull("x", call));
    assertNull(guard.invokeOrNull("x", call));

    assertEquals(2, calls.get());
    assertFalse(guard.isLatched(String.class));
  }

  @Test
  void doesNotLatchWhenTheMessageCannotBeAttributed() {
    AbstractMethodGuard guard = new AbstractMethodGuard();
    AtomicInteger calls = new AtomicInteger();

    for (AbstractMethodError error :
        new AbstractMethodError[] {
          new AbstractMethodError(), new AbstractMethodError("something else entirely")
        }) {
      assertNull(
          guard.invokeOrNull(
              "x",
              t -> {
                calls.incrementAndGet();
                throw error;
              }));
    }

    assertEquals(2, calls.get());
    assertFalse(guard.isLatched(String.class));
  }

  @Test
  void attributionRequiresTheWholeClassName() {
    assertTrue(AbstractMethodGuard.isAttributedTo(receiverError(String.class), String.class));
    // "java.lang.String" is a prefix of "java.lang.StringBuilder", but not the same class
    assertFalse(
        AbstractMethodGuard.isAttributedTo(receiverError(String.class), StringBuilder.class));
    assertFalse(
        AbstractMethodGuard.isAttributedTo(
            new AbstractMethodError("Receiver class " + String.class.getName()), String.class));
  }

  @Test
  void attributesTheJdk8MessageFormat() {
    // JDK 8 reports "<receiver class>.<method><descriptor>"
    String name = String.class.getName();

    assertTrue(
        AbstractMethodGuard.isAttributedTo(
            new AbstractMethodError(name + ".getClientInfo()Ljava/util/Properties;"),
            String.class));
    // a different class whose name merely starts with this one
    assertFalse(
        AbstractMethodGuard.isAttributedTo(
            new AbstractMethodError(name + "Builder.getClientInfo()Ljava/util/Properties;"),
            String.class));
    // a class in a package named like this class
    assertFalse(
        AbstractMethodGuard.isAttributedTo(
            new AbstractMethodError(name + ".Inner.getClientInfo()Ljava/util/Properties;"),
            String.class));
    assertFalse(AbstractMethodGuard.isAttributedTo(new AbstractMethodError(name), String.class));
    assertFalse(
        AbstractMethodGuard.isAttributedTo(new AbstractMethodError(name + "."), String.class));
  }

  @Test
  void checkedExceptionsPropagateAndDoNotLatch() {
    AbstractMethodGuard guard = new AbstractMethodGuard();
    SQLException failure = new SQLException("boom");

    SQLException thrown =
        assertThrows(
            SQLException.class,
            () ->
                guard.invokeOrNull(
                    "x",
                    t -> {
                      throw failure;
                    }));

    assertSame(failure, thrown);
    assertFalse(guard.isLatched(String.class));
  }

  @Test
  void unsupportedOperationYieldsNullOnEveryCallAndIsNeverLatched() {
    AbstractMethodGuard guard = new AbstractMethodGuard();
    AtomicInteger calls = new AtomicInteger();
    AbstractMethodGuard.Call<Object, String, RuntimeException> call =
        t -> {
          calls.incrementAndGet();
          throw new UnsupportedOperationException();
        };

    assertNull(guard.invokeOrNull("x", call));
    assertNull(guard.invokeOrNull("x", call));

    assertEquals(2, calls.get());
    assertFalse(guard.isLatched(String.class));
  }

  @Test
  void otherUncheckedExceptionsPropagateAndDoNotLatch() {
    AbstractMethodGuard guard = new AbstractMethodGuard();

    assertThrows(
        IllegalStateException.class,
        () ->
            guard.invokeOrNull(
                "x",
                t -> {
                  throw new IllegalStateException();
                }));
    assertFalse(guard.isLatched(String.class));
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
            AbstractMethodGuardTest.class.getClassLoader())) {
      Class<?> iface = loader.loadClass("I");
      Object impl = loader.loadClass("Impl").getDeclaredConstructor().newInstance();
      Method call = loader.loadClass("Caller").getMethod("call", iface);

      AtomicInteger calls = new AtomicInteger();
      AbstractMethodGuard guard = new AbstractMethodGuard();
      AbstractMethodGuard.Call<Object, Object, Exception> invoke =
          target -> {
            calls.incrementAndGet();
            try {
              return call.invoke(null, target);
            } catch (InvocationTargetException e) {
              if (e.getCause() instanceof AbstractMethodError) {
                throw (AbstractMethodError) e.getCause();
              }
              throw e;
            }
          };

      assertNull(guard.invokeOrNull(impl, invoke));
      assertNull(guard.invokeOrNull(impl, invoke));

      assertEquals(1, calls.get(), "second call should be skipped");
      assertTrue(guard.isLatched(impl.getClass()));
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
