package datadog.trace.util;

import static java.util.Collections.singletonList;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * What {@link AbstractMethodGuard} saves when an implementation lacks an interface method.
 *
 * <p>The missing method is real: {@code Impl} is built against an old {@code I}, and the caller
 * against a newer {@code I} that added {@code b()}, so the call raises the JVM's own {@link
 * AbstractMethodError}. Every arm reaches it through the same {@link MethodHandle}.
 *
 * <ul>
 *   <li>{@code unguardedMissing}: the status quo -- throw and catch on every call.
 *   <li>{@code guardedMissing}: the guard has latched {@code Impl}, so the call is skipped.
 *   <li>{@code guardedWrapperMissing}: the called object is a wrapper whose delegate lacks the
 *       method. The error names the delegate, so nothing may be latched and every call still
 *       throws. This is the price of staying safe; it should match {@code unguardedMissing}.
 *   <li>{@code unguardedPresent} / {@code guardedPresent}: the method exists. The difference is the
 *       guard's overhead on the path that works.
 * </ul>
 *
 * <p>The cost of a throw grows with the depth of the stack it fills in, which is why {@code depth}
 * is a parameter: a benchmark thread's stack is shallow, a request thread's is not.
 *
 * <p>Run with {@code ./gradlew :internal-api:jmh -Pjmh.includes=AbstractMethodGuardBenchmark
 * -Pjmh.profilers=gc}.
 *
 * <p>Results are ops/s, single thread, JDK 17.0.7 (Zulu), MacBook M1, 2 forks. They come from two
 * separate runs, so compare arms within a group, not across groups. JDK 8 and x86 are not measured.
 *
 * <pre>
 * Status quo and wrapper (an earlier run; neither arm depends on the latch)
 * Benchmark                                        (depth)      ops/s    B/op
 * AbstractMethodGuardBenchmark.unguardedMissing          0    218,369     896
 * AbstractMethodGuardBenchmark.unguardedMissing         50    162,090   2,256
 * AbstractMethodGuardBenchmark.guardedWrapperMissing     0    226,385     896
 * AbstractMethodGuardBenchmark.guardedWrapperMissing    50    165,602   2,256
 *
 * Latched and working path (final code, plain flag)
 * AbstractMethodGuardBenchmark.guardedMissing            0  199,493,000     0
 * AbstractMethodGuardBenchmark.guardedMissing           50   30,081,622     0
 * AbstractMethodGuardBenchmark.guardedPresent            0  207,855,250     0
 * AbstractMethodGuardBenchmark.guardedPresent           50   30,509,308     0
 * AbstractMethodGuardBenchmark.unguardedPresent          0  223,546,636     0
 * AbstractMethodGuardBenchmark.unguardedPresent         50   31,579,822     0
 * </pre>
 *
 * A latched class costs about 5 ns instead of 4.6-6.2 us and allocates nothing. A wrapper, which
 * cannot be latched, performs like the status quo. On the working path the guard adds about 0.3 ns
 * (depth 0) to 1.1 ns (depth 50); a volatile flag measured 0.6 ns and 5.8 ns.
 */
@Fork(2)
@Warmup(iterations = 3)
@Measurement(iterations = 4)
@Threads(1)
@State(Scope.Benchmark)
public class AbstractMethodGuardBenchmark {

  @Param({"0", "50"})
  int depth;

  private Path dir;
  private URLClassLoader loader;
  private MethodHandle call;
  private Object impl;
  private Object full;
  private Object wrapper;

  private AbstractMethodGuard.Call<Object, Object, RuntimeException> invoke;

  private static final AbstractMethodGuard MISSING = new AbstractMethodGuard();
  private static final AbstractMethodGuard WRAPPER = new AbstractMethodGuard();
  private static final AbstractMethodGuard PRESENT = new AbstractMethodGuard();

  @Setup
  public void setup() throws Throwable {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("needs a JDK to build the classes under test");
    }
    dir = Files.createTempDirectory("abstract-method-guard");
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
        "Full",
        "public class Full implements I {"
            + " public String a() { return \"a\"; } public String b() { return \"b\"; } }");
    compile(
        compiler,
        newDir,
        newDir,
        "Wrapper",
        "public class Wrapper implements I { private final I delegate;"
            + " public Wrapper(I delegate) { this.delegate = delegate; }"
            + " public String a() { return delegate.a(); }"
            + " public String b() { return delegate.b(); } }");
    compile(
        compiler,
        newDir,
        newDir,
        "Caller",
        "public class Caller { public static String call(I i) { return i.b(); } }");

    // the new interface shadows the old one; Impl was built against the old one
    loader =
        new URLClassLoader(
            new URL[] {newDir.toUri().toURL(), oldDir.toUri().toURL()},
            AbstractMethodGuardBenchmark.class.getClassLoader());
    Class<?> iface = loader.loadClass("I");
    impl = loader.loadClass("Impl").getDeclaredConstructor().newInstance();
    full = loader.loadClass("Full").getDeclaredConstructor().newInstance();
    wrapper = loader.loadClass("Wrapper").getDeclaredConstructor(iface).newInstance(impl);

    call =
        MethodHandles.publicLookup()
            .findStatic(
                loader.loadClass("Caller"), "call", MethodType.methodType(String.class, iface))
            .asType(MethodType.methodType(Object.class, Object.class));

    invoke =
        target -> {
          try {
            return (Object) call.invokeExact(target);
          } catch (RuntimeException | Error e) {
            throw e;
          } catch (Throwable e) {
            throw new IllegalStateException(e);
          }
        };

    // reach the steady state: the guard has already met the deficient class
    MISSING.invokeOrNull(impl, invoke);
    if (!MISSING.isLatched(impl.getClass())) {
      throw new IllegalStateException("expected the guard to latch " + impl.getClass());
    }
    WRAPPER.invokeOrNull(wrapper, invoke);
    if (WRAPPER.isLatched(wrapper.getClass()) || WRAPPER.isLatched(impl.getClass())) {
      throw new IllegalStateException("a wrapper must never be latched");
    }
  }

  @TearDown
  public void tearDown() throws IOException {
    loader.close();
  }

  @Benchmark
  public Object unguardedMissing() {
    return descend(depth, 0);
  }

  @Benchmark
  public Object guardedMissing() {
    return descend(depth, 1);
  }

  @Benchmark
  public Object guardedWrapperMissing() {
    return descend(depth, 2);
  }

  @Benchmark
  public Object unguardedPresent() {
    return descend(depth, 3);
  }

  @Benchmark
  public Object guardedPresent() {
    return descend(depth, 4);
  }

  /** Grows the stack so that a throw has a realistic amount to fill in. */
  private Object descend(int remaining, int kind) {
    if (remaining > 0) {
      return descend(remaining - 1, kind);
    }
    switch (kind) {
      case 0:
        try {
          return invoke.apply(impl);
        } catch (AbstractMethodError e) {
          return null;
        }
      case 1:
        return MISSING.invokeOrNull(impl, invoke);
      case 2:
        return WRAPPER.invokeOrNull(wrapper, invoke);
      case 3:
        return invoke.apply(full);
      default:
        return PRESENT.invokeOrNull(full, invoke);
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
    if (result != 0) {
      throw new IllegalStateException("compiling " + name + " failed");
    }
  }
}
