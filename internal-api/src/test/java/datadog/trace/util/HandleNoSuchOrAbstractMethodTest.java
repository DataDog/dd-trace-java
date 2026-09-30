package datadog.trace.util;

import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

class HandleNoSuchOrAbstractMethodTest {

  /** What a call site writes: {@code get} delegating to {@code handleNoSuchOrAbstractMethod}. */
  private abstract static class Handling<T, R, E extends Exception> extends ClassLatch<T, R, E> {
    final AtomicInteger calls = new AtomicInteger();

    protected abstract R invoke(T target) throws E;

    @Override
    protected final R get(T target) throws E {
      return handleNoSuchOrAbstractMethod(
          target,
          t -> {
            calls.incrementAndGet();
            return invoke(t);
          });
    }
  }

  private static final class Throwing extends Handling<Object, String, Exception> {
    final Throwable failure;

    Throwing(Throwable failure) {
      this.failure = failure;
    }

    @Override
    protected String invoke(Object target) throws Exception {
      if (failure instanceof Exception) {
        throw (Exception) failure;
      }
      throw (Error) failure;
    }
  }

  @Test
  void noSuchMethodYieldsNullAndLatchesTheTargetsClass() throws Exception {
    Throwing latch = new Throwing(new NoSuchMethodError("I.b()Ljava/lang/String;"));

    assertNull(latch.tryGetOrNull("x"));
    assertNull(latch.tryGetOrNull("y"));
    assertNull(latch.tryGetOrNull("z"));

    assertEquals(1, latch.calls.get(), "later calls should be skipped");
    assertTrue(latch.isLatched("x"));
  }

  @Test
  void noSuchMethodNeverLatchesTheWholeSite() throws Exception {
    // the message names the declared type, not the receiver, so it cannot be attributed to a class;
    // latching only the target's key means another class still gets its own attempt
    Throwing latch = new Throwing(new NoSuchMethodError("I.b()Ljava/lang/String;"));
    latch.tryGetOrNull("x");

    assertFalse(latch.isLatched(Integer.valueOf(1)));
    assertNull(latch.tryGetOrNull(Integer.valueOf(1)));
    assertEquals(2, latch.calls.get());
    assertTrue(latch.isLatched(Integer.valueOf(1)));
  }

  @Test
  void abstractMethodStillLatchesOnlyWhenTheErrorNamesTheKey() throws Exception {
    Throwing named =
        new Throwing(
            new AbstractMethodError(
                "Receiver class "
                    + String.class.getName()
                    + " does not define or inherit an implementation of the resolved method"));
    assertNull(named.tryGetOrNull("x"));
    assertTrue(named.isLatched("x"));

    Throwing unnamed = new Throwing(new AbstractMethodError("something else entirely"));
    assertNull(unnamed.tryGetOrNull("x"));
    assertNull(unnamed.tryGetOrNull("x"));
    assertFalse(unnamed.isLatched("x"));
    assertEquals(2, unnamed.calls.get());
  }

  @Test
  void unsupportedOperationIsSwallowedAndNeverLatched() throws Exception {
    Throwing latch = new Throwing(new UnsupportedOperationException());

    assertNull(latch.tryGetOrNull("x"));
    assertNull(latch.tryGetOrNull("x"));

    assertEquals(2, latch.calls.get());
    assertFalse(latch.isLatched("x"));
  }

  @Test
  void otherFailuresPropagateWithoutLatching() {
    Throwing checked = new Throwing(new SQLException("boom"));
    assertThrows(SQLException.class, () -> checked.tryGetOrNull("x"));
    assertFalse(checked.isLatched("x"));

    Throwing unchecked = new Throwing(new IllegalStateException());
    assertThrows(IllegalStateException.class, () -> unchecked.tryGetOrNull("x"));
    assertFalse(unchecked.isLatched("x"));
  }

  /**
   * A real {@link NoSuchMethodError}: a caller built against an interface that has {@code b()}, run
   * against one that does not.
   */
  @Test
  void latchesARealNoSuchMethodError(@TempDir Path dir) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assumeTrue(compiler != null, "needs a JDK");

    Path oldDir = Files.createDirectory(dir.resolve("old"));
    Path newDir = Files.createDirectory(dir.resolve("new"));
    compile(compiler, oldDir, null, "I", "public interface I { String a(); String b(); }");
    compile(
        compiler,
        oldDir,
        oldDir,
        "Caller",
        "public class Caller { public static String call(I i) { return i.b(); } }");
    compile(compiler, newDir, null, "I", "public interface I { String a(); }");
    compile(
        compiler,
        newDir,
        newDir,
        "Impl",
        "public class Impl implements I { public String a() { return \"a\"; } }");

    try (URLClassLoader loader =
        new URLClassLoader(
            new URL[] {newDir.toUri().toURL(), oldDir.toUri().toURL()},
            HandleNoSuchOrAbstractMethodTest.class.getClassLoader())) {
      Class<?> iface = loader.loadClass("I");
      Object impl = loader.loadClass("Impl").getDeclaredConstructor().newInstance();
      Method call = loader.loadClass("Caller").getMethod("call", iface);

      Handling<Object, Object, Exception> latch =
          new Handling<Object, Object, Exception>() {
            @Override
            protected Object invoke(Object target) throws Exception {
              try {
                return call.invoke(null, target);
              } catch (InvocationTargetException e) {
                if (e.getCause() instanceof NoSuchMethodError) {
                  throw (NoSuchMethodError) e.getCause();
                }
                throw e;
              }
            }
          };

      assertNull(latch.tryGetOrNull(impl));
      assertNull(latch.tryGetOrNull(impl));

      assertEquals(1, latch.calls.get(), "second call should be skipped");
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
