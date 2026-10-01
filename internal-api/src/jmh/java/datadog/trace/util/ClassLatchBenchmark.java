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
 * What {@link ClassLatch#handleAbstractMethod} saves when an implementation lacks an interface
 * method.
 *
 * <p>The missing method is real: {@code Impl} is built against an old {@code I}, and the caller
 * against a newer {@code I} that added {@code b()}, so the call raises the JVM's own {@link
 * AbstractMethodError}. Every arm reaches it through the same {@link MethodHandle}. The latches are
 * {@code static final} anonymous subclasses, as they would be at a call site, and each arm has its
 * own method so that no arm's profile is shaped by another's.
 *
 * <ul>
 *   <li>{@code unguardedMissing}: the status quo -- throw and catch on every call.
 *   <li>{@code latchedMissing}: the latch has latched {@code Impl}, so the call is skipped.
 *   <li>{@code latchedWrapperMissing}: the called object is a wrapper whose delegate lacks the
 *       method. The error names the delegate, so nothing may be latched and every call still
 *       throws. This is the price of staying safe; it should match {@code unguardedMissing}.
 *   <li>{@code unguardedPresent} / {@code latchedPresent}: the method exists. The difference is the
 *       latch's overhead on the path that works.
 *   <li>{@code subclassMissing} / {@code subclassPresent}: the same policy written as a reusable
 *       abstract subclass overriding {@code invoke}, instead of a method reference passed to {@code
 *       handleAbstractMethod}. It is a benchmark-local copy: it shows whether a dedicated class
 *       would be worth shipping.
 * </ul>
 *
 * <p>The cost of a throw grows with the depth of the stack it fills in, which is why {@code depth}
 * is a parameter: a benchmark thread's stack is shallow, a request thread's is not.
 *
 * <p>Run with {@code ./gradlew :internal-api:jmh -Pjmh.includes=ClassLatchBenchmark
 * -Pjmh.profilers=gc}.
 *
 * <p>Results: ops/s, single thread, Zulu 17.0.7, MacBook M1, 2 forks, one run. JDK 8 and x86 are
 * not measured.
 *
 * <pre>
 * Benchmark                                        (depth)       ops/s   B/op
 * ClassLatchBenchmark.unguardedMissing                   0     220,977    896
 * ClassLatchBenchmark.latchedWrapperMissing              0     228,684    896
 * ClassLatchBenchmark.latchedMissing                     0 201,587,502      0
 * ClassLatchBenchmark.subclassMissing                    0 201,915,340      0
 * ClassLatchBenchmark.unguardedPresent                   0 223,537,261      0
 * ClassLatchBenchmark.latchedPresent                     0 203,305,896      0
 * ClassLatchBenchmark.subclassPresent                    0 227,339,864      0   (+-22%)
 *
 * ClassLatchBenchmark.unguardedMissing                  50     164,166  2,256
 * ClassLatchBenchmark.latchedWrapperMissing             50     166,284  2,256
 * ClassLatchBenchmark.unguardedPresent                  50  32,451,009      0
 * ClassLatchBenchmark.latchedPresent                    50  28,001,510      0
 * </pre>
 *
 * A latched class costs about 5 ns instead of about 4.5 us and allocates nothing instead of 896 B
 * per call. A wrapper, which cannot be latched, performs like the status quo. On the working path
 * the latch adds about 0.5 ns at depth 0. At depth 0 the method-reference form ({@code
 * handleAbstractMethod}) and the dedicated-subclass form are indistinguishable on the latched path.
 *
 * <p><b>Depth 50, latched arms: not reported.</b> Each fork was stable, but forks landed in
 * different compiled states. {@code latchedMissing} ran at about 11.6M ops/s in one fork and about
 * 31.7M in the other, {@code subclassMissing} at about 11.5M in both, {@code subclassPresent} at
 * about 36M and 32M. So the mean and its error are two modes averaged, and the ranking of the two
 * forms at depth 50 is not established. Both modes (roughly 85 ns and 32 ns) are far below the
 * status quo of about 6 us. The cause was not investigated.
 *
 * <p>Results, one run: Zulu 17.0.7 (HotSpot), MacBook M1, single thread, 5 forks, on a laptop with
 * normal background activity (load about 4). JDK 8 and x86 are not measured. {@code latched} uses
 * {@code handleAbstractMethod} with a method reference; {@code subclass} is a benchmark-local
 * dedicated subclass, for comparison.
 *
 * <pre>
 * Benchmark                (depth)          ops/s     ns/op    err   B/op
 * unguardedMissing               0        227,738    4391.0   0.4%    896
 * latchedWrapperMissing          0        232,316    4304.5   0.5%    896
 * latchedMissing                 0    205,379,219      4.87   0.6%      0
 * subclassMissing                0    205,736,674      4.86   0.6%      0
 * unguardedPresent               0    224,381,889      4.46   0.4%      0
 * latchedPresent                 0    209,902,233      4.76   0.3%      0
 * subclassPresent                0    211,697,167      4.72   0.1%      0
 *
 * unguardedMissing              50        165,183    6053.9   1.2%   2256
 * latchedWrapperMissing         50        171,300    5837.7   0.5%   2256
 * latchedMissing                50     11,741,580      85.2   0.8%      0
 * subclassMissing               50     11,842,809      84.4   0.3%      0
 * unguardedPresent              50     35,092,870      28.5   3.4%      0
 * latchedPresent                50     35,079,191      28.5   3.3%      0
 * subclassPresent               50     36,004,724      27.8   3.0%      0
 * </pre>
 *
 * A latched class costs about 4.9 ns where the status quo costs about 4.4 us (6.1 us at depth 50),
 * and allocates nothing where the status quo allocates 896 B (2,256 B): roughly 900 times cheaper
 * at depth 0 and 70 times at depth 50. A wrapper, which cannot be latched, performs like the status
 * quo. The method-reference form and the dedicated subclass are indistinguishable (within 1%). The
 * latch adds about 0.3 ns on the working path at depth 0 and nothing measurable at depth 50.
 *
 * <p>Unexplained: at depth 50 a latched skip (about 85 ns) is slower than a call that runs (about
 * 28.5 ns), though at depth 0 the skip is as cheap as expected. All five forks agreed (11.7M to
 * 11.9M ops/s per fork for the latched arm), so it is not noise. It may be a JIT effect of the
 * benchmark's recursion rather than a property of the latch, but that was not tested.
 */
@Fork(2)
@Warmup(iterations = 3)
@Measurement(iterations = 4)
@Threads(1)
@State(Scope.Benchmark)
public class ClassLatchBenchmark {

  @Param({"0", "50"})
  int depth;

  private Path dir;
  private URLClassLoader loader;
  private Object impl;
  private Object full;
  private Object wrapper;

  /** Set in {@link #setup}; every arm and latch calls through it. */
  private static MethodHandle handle;

  private static Object invokeHandle(Object target) {
    try {
      return (Object) handle.invokeExact(target);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    }
  }

  private static final ClassLatch<Object, Object, RuntimeException> MISSING =
      new ClassLatch<Object, Object, RuntimeException>() {
        @Override
        protected Object apply(Object target) {
          return handleAbstractMethod(target, ClassLatchBenchmark::invokeHandle);
        }
      };

  private static final ClassLatch<Object, Object, RuntimeException> WRAPPER =
      new ClassLatch<Object, Object, RuntimeException>() {
        @Override
        protected Object apply(Object target) {
          return handleAbstractMethod(target, ClassLatchBenchmark::invokeHandle);
        }
      };

  private static final ClassLatch<Object, Object, RuntimeException> PRESENT =
      new ClassLatch<Object, Object, RuntimeException>() {
        @Override
        protected Object apply(Object target) {
          return handleAbstractMethod(target, ClassLatchBenchmark::invokeHandle);
        }
      };

  // the policy as a reusable subclass, for comparison only
  private abstract static class SubclassStyle extends ClassLatch<Object, Object, RuntimeException> {
    protected abstract Object invoke(Object target);

    @Override
    protected final Object apply(Object target) {
      try {
        return invoke(target);
      } catch (AbstractMethodError e) {
        latchIfNamed(target, e);
        return null;
      } catch (UnsupportedOperationException e) {
        return null;
      }
    }
  }

  private static final SubclassStyle SUBCLASS_MISSING =
      new SubclassStyle() {
        @Override
        protected Object invoke(Object target) {
          return invokeHandle(target);
        }
      };

  private static final SubclassStyle SUBCLASS_PRESENT =
      new SubclassStyle() {
        @Override
        protected Object invoke(Object target) {
          return invokeHandle(target);
        }
      };

  @Setup
  public void setup() throws Throwable {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("needs a JDK to build the classes under test");
    }
    dir = Files.createTempDirectory("abstract-method-latch");
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
            ClassLatchBenchmark.class.getClassLoader());
    Class<?> iface = loader.loadClass("I");
    impl = loader.loadClass("Impl").getDeclaredConstructor().newInstance();
    full = loader.loadClass("Full").getDeclaredConstructor().newInstance();
    wrapper = loader.loadClass("Wrapper").getDeclaredConstructor(iface).newInstance(impl);

    handle =
        MethodHandles.publicLookup()
            .findStatic(
                loader.loadClass("Caller"), "call", MethodType.methodType(String.class, iface))
            .asType(MethodType.methodType(Object.class, Object.class));

    // reach the steady state: the latch has already met the deficient class
    MISSING.tryApplyOrNull(impl);
    if (!MISSING.isLatched(impl)) {
      throw new IllegalStateException("expected the latch to latch " + impl.getClass());
    }
    WRAPPER.tryApplyOrNull(wrapper);
    if (WRAPPER.isLatched(wrapper) || WRAPPER.isLatched(impl)) {
      throw new IllegalStateException("a wrapper must never be latched");
    }
    SUBCLASS_MISSING.tryApplyOrNull(impl);
    if (!SUBCLASS_MISSING.isLatched(impl)) {
      throw new IllegalStateException(
          "expected the subclass-style latch to latch " + impl.getClass());
    }
  }

  @TearDown
  public void tearDown() throws IOException {
    loader.close();
  }

  @Benchmark
  public Object unguardedMissing() {
    return unguardedMissing(depth);
  }

  @Benchmark
  public Object latchedMissing() {
    return latchedMissing(depth);
  }

  @Benchmark
  public Object latchedWrapperMissing() {
    return latchedWrapperMissing(depth);
  }

  @Benchmark
  public Object unguardedPresent() {
    return unguardedPresent(depth);
  }

  @Benchmark
  public Object latchedPresent() {
    return latchedPresent(depth);
  }

  @Benchmark
  public Object subclassMissing() {
    return subclassMissing(depth);
  }

  @Benchmark
  public Object subclassPresent() {
    return subclassPresent(depth);
  }

  // Each arm descends on its own so that a throw has a realistic amount of stack to fill in.

  private Object unguardedMissing(int remaining) {
    if (remaining > 0) {
      return unguardedMissing(remaining - 1);
    }
    try {
      return invokeHandle(impl);
    } catch (AbstractMethodError e) {
      return null;
    }
  }

  private Object latchedMissing(int remaining) {
    return remaining > 0 ? latchedMissing(remaining - 1) : MISSING.tryApplyOrNull(impl);
  }

  private Object latchedWrapperMissing(int remaining) {
    return remaining > 0 ? latchedWrapperMissing(remaining - 1) : WRAPPER.tryApplyOrNull(wrapper);
  }

  private Object unguardedPresent(int remaining) {
    return remaining > 0 ? unguardedPresent(remaining - 1) : invokeHandle(full);
  }

  private Object latchedPresent(int remaining) {
    return remaining > 0 ? latchedPresent(remaining - 1) : PRESENT.tryApplyOrNull(full);
  }

  private Object subclassMissing(int remaining) {
    return remaining > 0 ? subclassMissing(remaining - 1) : SUBCLASS_MISSING.tryApplyOrNull(impl);
  }

  private Object subclassPresent(int remaining) {
    return remaining > 0 ? subclassPresent(remaining - 1) : SUBCLASS_PRESENT.tryApplyOrNull(full);
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
