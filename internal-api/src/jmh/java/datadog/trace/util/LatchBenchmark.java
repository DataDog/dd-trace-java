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
 * What {@link Latch} saves when a field read fails the same way every time, and what it costs on
 * the path that works.
 *
 * <p>The missing field is real: {@code Reader} is built against a {@code Holder} that has a {@code
 * flag} field, and run against a {@code Holder} that does not, so its {@code getfield} raises the
 * JVM's own {@link NoSuchFieldError}. Every arm reaches the read through the same {@link
 * MethodHandle}, and each arm has its own method so that no arm's profile is shaped by another's.
 *
 * <ul>
 *   <li>{@code unguarded}: the status quo -- read and catch on every call.
 *   <li>{@code volatileFlag}: a hand-rolled {@code static volatile boolean}, the shape this latch
 *       replaced in the Jackson interner lookup.
 *   <li>{@code plainFlag}: the same with a plain {@code static boolean}. The difference from {@code
 *       latch} is the cost of the abstraction itself.
 *   <li>{@code latch}: a {@code static final} anonymous {@link Latch} subclass.
 * </ul>
 *
 * Each has a {@code Missing} form, already latched so that the steady state is measured, and a
 * {@code Present} form, where the field exists and the read succeeds. The latches' flags are never
 * set on the present path.
 *
 * <p>The cost of a throw grows with the depth of the stack it fills in, which is why {@code depth}
 * is a parameter. At depth 50 an earlier benchmark's forks landed in different compiled states, so
 * read the per-fork iterations and not only the mean.
 *
 * <p>Run with {@code ./gradlew :internal-api:jmh -Pjmh.includes=LatchBenchmark -Pjmh.profilers=gc}.
 *
 * <p>Results, one run: Zulu 17.0.7 (HotSpot), MacBook M1, single thread, 5 forks, on a laptop with
 * normal background activity (load about 4). {@code unguarded} is the status quo, {@code
 * volatileFlag} and {@code plainFlag} are hand-rolled flags, and {@code latch} is this class. JDK 8
 * and x86 are not measured.
 *
 * <pre>
 * Benchmark              (depth)          ops/s     ns/op    err   B/op
 * unguardedMissing             0        286,635    3488.8   0.4%    768
 * volatileFlagMissing          0    409,799,340      2.44   1.2%      0
 * plainFlagMissing             0    458,120,051      2.18   0.6%      0
 * latchMissing                 0    458,763,738      2.18   0.8%      0
 * unguardedPresent             0    298,248,659      3.35   0.5%      0
 * volatileFlagPresent          0    247,533,590      4.04   0.4%      0
 * plainFlagPresent             0    281,751,834      3.55   0.2%      0
 * latchPresent                 0    282,939,106      3.53   0.2%      0
 *
 * unguardedMissing            50        196,076    5100.1   0.5%   2128
 * volatileFlagMissing         50     33,753,591      29.6   0.5%      0
 * plainFlagMissing            50     36,099,245      27.7   0.4%      0
 * latchMissing                50     35,428,255      28.2   0.3%      0
 * unguardedPresent            50     30,037,705      33.3   0.5%      0
 * volatileFlagPresent         50     31,199,284      32.1   3.1%      0
 * plainFlagPresent            50     25,366,102      39.4   6.2%      0
 * latchPresent                50     31,938,175      31.3   0.9%      0
 * </pre>
 *
 * A latched skip costs about 2.2 ns where the status quo costs about 3.5 us at depth 0 (5.1 us at
 * depth 50) and allocates 768 B (2,128 B); that is roughly 1,600 times cheaper at depth 0 and 180
 * times at depth 50. {@code Latch} is as cheap as a hand-rolled plain flag (2.18 against 2.18 ns
 * skipped, 3.53 against 3.55 ns on the working path), so the abstraction costs nothing measurable.
 * A volatile flag costs more: about 0.5 ns over a plain flag on the working path at depth 0 (4.04
 * against 3.55 ns) and about 0.3 ns when skipping (2.44 against 2.18 ns).
 *
 * <p>At depth 50, read only the skipped arms (28 to 30 ns, consistent across forks): the
 * working-path arms span 31 to 39 ns and the plain flag, which is the same logic as {@code latch},
 * came out slowest with a 6% error and one fork at 28.9M against 24.2M to 24.8M ops/s for the
 * others. That spread is JIT and recursion noise, not a difference between the designs.
 */
@Fork(3)
@Warmup(iterations = 3)
@Measurement(iterations = 4)
@Threads(1)
@State(Scope.Benchmark)
public class LatchBenchmark {

  @Param({"0", "50"})
  int depth;

  private Path dir;
  private URLClassLoader missingLoader;
  private URLClassLoader presentLoader;
  private Object missingTarget;
  private Object presentTarget;

  /** Set in {@link #setup}; every arm and latch reads through these. */
  private static MethodHandle readMissing;

  private static MethodHandle readPresent;

  private static boolean read(MethodHandle handle, Object target) {
    try {
      return (boolean) handle.invokeExact(target);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IllegalStateException(e);
    }
  }

  private static volatile boolean volatileMissingLatched;
  private static volatile boolean volatilePresentLatched;
  private static boolean plainMissingLatched;
  private static boolean plainPresentLatched;

  private static final Latch<Object, Boolean, RuntimeException> LATCH_MISSING =
      new Latch<Object, Boolean, RuntimeException>() {
        @Override
        protected Boolean apply(Object target) {
          try {
            return read(readMissing, target);
          } catch (NoSuchFieldError e) {
            latch();
            throw e;
          }
        }
      };

  private static final Latch<Object, Boolean, RuntimeException> LATCH_PRESENT =
      new Latch<Object, Boolean, RuntimeException>() {
        @Override
        protected Boolean apply(Object target) {
          try {
            return read(readPresent, target);
          } catch (NoSuchFieldError e) {
            latch();
            throw e;
          }
        }
      };

  @Setup
  public void setup() throws Throwable {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new IllegalStateException("needs a JDK to build the classes under test");
    }
    dir = Files.createTempDirectory("latch-benchmark");
    Path withField = Files.createDirectory(dir.resolve("with"));
    Path withoutField = Files.createDirectory(dir.resolve("without"));

    // Reader is always built against a Holder that has the field
    compile(compiler, withField, null, "Holder", "public class Holder { public boolean flag; }");
    compile(
        compiler,
        withField,
        withField,
        "Reader",
        "public class Reader { public static boolean read(Holder h) { return h.flag; } }");
    // ...and the missing case runs it against a Holder that does not
    compile(
        compiler, withoutField, null, "Holder", "public class Holder { public boolean other; }");

    missingLoader =
        new URLClassLoader(
            new URL[] {withoutField.toUri().toURL(), withField.toUri().toURL()},
            LatchBenchmark.class.getClassLoader());
    presentLoader =
        new URLClassLoader(
            new URL[] {withField.toUri().toURL()}, LatchBenchmark.class.getClassLoader());

    missingTarget = missingLoader.loadClass("Holder").getDeclaredConstructor().newInstance();
    presentTarget = presentLoader.loadClass("Holder").getDeclaredConstructor().newInstance();
    readMissing = handle(missingLoader);
    readPresent = handle(presentLoader);

    // reach the steady state: every missing-field guard has already met the failure once
    try {
      read(readMissing, missingTarget);
      throw new IllegalStateException("expected a NoSuchFieldError");
    } catch (NoSuchFieldError expected) {
      // the field really is missing
    }
    try {
      LATCH_MISSING.tryApply(missingTarget);
    } catch (NoSuchFieldError expected) {
      // the first failure is rethrown
    }
    volatileMissingLatched = true;
    plainMissingLatched = true;
    if (!LATCH_MISSING.isLatched()) {
      throw new IllegalStateException("expected the latch to latch");
    }
    if (LATCH_PRESENT.isLatched()) {
      throw new IllegalStateException("the present latch must not be latched");
    }
  }

  private static MethodHandle handle(URLClassLoader loader) throws Throwable {
    Class<?> holder = loader.loadClass("Holder");
    return MethodHandles.publicLookup()
        .findStatic(
            loader.loadClass("Reader"), "read", MethodType.methodType(boolean.class, holder))
        .asType(MethodType.methodType(boolean.class, Object.class));
  }

  @TearDown
  public void tearDown() throws IOException {
    missingLoader.close();
    presentLoader.close();
  }

  @Benchmark
  public boolean unguardedMissing() {
    return unguardedMissing(depth);
  }

  @Benchmark
  public boolean volatileFlagMissing() {
    return volatileFlagMissing(depth);
  }

  @Benchmark
  public boolean plainFlagMissing() {
    return plainFlagMissing(depth);
  }

  @Benchmark
  public boolean latchMissing() {
    return latchMissing(depth);
  }

  @Benchmark
  public boolean unguardedPresent() {
    return unguardedPresent(depth);
  }

  @Benchmark
  public boolean volatileFlagPresent() {
    return volatileFlagPresent(depth);
  }

  @Benchmark
  public boolean plainFlagPresent() {
    return plainFlagPresent(depth);
  }

  @Benchmark
  public boolean latchPresent() {
    return latchPresent(depth);
  }

  // Each arm descends on its own so that a throw has a realistic amount of stack to fill in.

  private boolean unguardedMissing(int remaining) {
    if (remaining > 0) {
      return unguardedMissing(remaining - 1);
    }
    try {
      return read(readMissing, missingTarget);
    } catch (NoSuchFieldError e) {
      return true;
    }
  }

  private boolean volatileFlagMissing(int remaining) {
    if (remaining > 0) {
      return volatileFlagMissing(remaining - 1);
    }
    if (volatileMissingLatched) {
      return true;
    }
    try {
      return read(readMissing, missingTarget);
    } catch (NoSuchFieldError e) {
      volatileMissingLatched = true;
      throw e;
    }
  }

  private boolean plainFlagMissing(int remaining) {
    if (remaining > 0) {
      return plainFlagMissing(remaining - 1);
    }
    if (plainMissingLatched) {
      return true;
    }
    try {
      return read(readMissing, missingTarget);
    } catch (NoSuchFieldError e) {
      plainMissingLatched = true;
      throw e;
    }
  }

  private boolean latchMissing(int remaining) {
    return remaining > 0
        ? latchMissing(remaining - 1)
        : LATCH_MISSING.tryApplyOrDefault(missingTarget, Boolean.TRUE);
  }

  private boolean unguardedPresent(int remaining) {
    return remaining > 0 ? unguardedPresent(remaining - 1) : read(readPresent, presentTarget);
  }

  private boolean volatileFlagPresent(int remaining) {
    if (remaining > 0) {
      return volatileFlagPresent(remaining - 1);
    }
    if (volatilePresentLatched) {
      return true;
    }
    try {
      return read(readPresent, presentTarget);
    } catch (NoSuchFieldError e) {
      volatilePresentLatched = true;
      throw e;
    }
  }

  private boolean plainFlagPresent(int remaining) {
    if (remaining > 0) {
      return plainFlagPresent(remaining - 1);
    }
    if (plainPresentLatched) {
      return true;
    }
    try {
      return read(readPresent, presentTarget);
    } catch (NoSuchFieldError e) {
      plainPresentLatched = true;
      throw e;
    }
  }

  private boolean latchPresent(int remaining) {
    return remaining > 0
        ? latchPresent(remaining - 1)
        : LATCH_PRESENT.tryApplyOrDefault(presentTarget, Boolean.TRUE);
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
