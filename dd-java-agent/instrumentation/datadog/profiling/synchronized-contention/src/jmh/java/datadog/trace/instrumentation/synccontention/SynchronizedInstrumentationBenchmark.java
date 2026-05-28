package datadog.trace.instrumentation.synccontention;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures the per-class bytecode transformation overhead of {@link SynchronizedRewritingVisitor}.
 *
 * <p>Three benchmarks isolate different cost layers:
 *
 * <ol>
 *   <li>{@code baseline} — pass-through with no special writer flags (lower bound; no frame
 *       recomputation).
 *   <li>{@code computeFrames} — production writer flags ({@code COMPUTE_FRAMES} + {@code
 *       EXPAND_FRAMES}) without the rewriting visitor. Isolates the cost of frame recomputation,
 *       which is the dominant concern raised during code review.
 *   <li>{@code withRewrite} — full production path: {@code COMPUTE_FRAMES} + {@link
 *       SynchronizedRewritingVisitor.RewritingClassVisitor}. This is the path taken by ByteBuddy
 *       for every loaded class on JDK 21+ when the synchronized-contention instrumentation is
 *       enabled.
 * </ol>
 *
 * <p>The difference {@code withRewrite - computeFrames} is the marginal cost of the visitor itself.
 * The difference {@code computeFrames - baseline} is the cost of {@code COMPUTE_FRAMES} frame
 * recomputation, which triggers full per-method dataflow analysis.
 *
 * <p>Three fixture variants exercise the visitor at different {@code synchronized} densities:
 *
 * <ul>
 *   <li>{@code SyncMethodFixture} — class with 3 {@code synchronized} methods (dense rewriting).
 *   <li>{@code SyncBlockFixture} — class with 3 {@code synchronized} blocks (typical pattern).
 *   <li>{@code NoSyncFixture} — class with no monitors (common case; visitor is a structural no-op,
 *       frame recomputation still runs).
 * </ul>
 *
 * <p>Run with: {@code ./gradlew
 * :dd-java-agent:instrumentation:datadog:profiling:synchronized-contention:jmh}
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1)
public class SynchronizedInstrumentationBenchmark {

  @Param({"SyncMethodFixture", "SyncBlockFixture", "NoSyncFixture"})
  public String fixtureName;

  private byte[] classBytes;

  @Setup
  public void setup() throws IOException {
    String resourcePath =
        SynchronizedInstrumentationBenchmark.class.getName().replace('.', '/')
            + "$"
            + fixtureName
            + ".class";
    try (InputStream in =
        SynchronizedInstrumentationBenchmark.class
            .getClassLoader()
            .getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IOException("class resource not found: " + resourcePath);
      }
      ByteArrayOutputStream buf = new ByteArrayOutputStream();
      byte[] tmp = new byte[4096];
      int n;
      while ((n = in.read(tmp)) >= 0) {
        buf.write(tmp, 0, n);
      }
      classBytes = buf.toByteArray();
    }
  }

  /**
   * Pass-through with no {@code COMPUTE_FRAMES} — establishes the minimum transformation cost (only
   * parse + serialize, no dataflow analysis).
   */
  @Benchmark
  public byte[] baseline() {
    ClassReader reader = new ClassReader(classBytes);
    ClassWriter writer = new ClassWriter(0);
    reader.accept(writer, ClassReader.EXPAND_FRAMES);
    return writer.toByteArray();
  }

  /**
   * Production writer flags without the rewriting visitor. Isolates the cost of {@code
   * COMPUTE_FRAMES}: ASM re-derives the entire stack map table for every method via dataflow
   * analysis, loading referenced types through the classloader.
   */
  @Benchmark
  public byte[] computeFrames() {
    ClassReader reader = new ClassReader(classBytes);
    ClassWriter writer = safeComputeFramesWriter(reader);
    reader.accept(writer, ClassReader.EXPAND_FRAMES);
    return writer.toByteArray();
  }

  /**
   * Full production path as applied by {@link SynchronizedContentionInstrumentation} on JDK 21+:
   * {@code COMPUTE_FRAMES} frame recomputation plus the monitor-rewriting visitor. Every class
   * loaded by the JVM passes through this path when the instrumentation is enabled.
   */
  @Benchmark
  public byte[] withRewrite() {
    ClassReader reader = new ClassReader(classBytes);
    ClassWriter writer = safeComputeFramesWriter(reader);
    ClassVisitor cv = new SynchronizedRewritingVisitor.RewritingClassVisitor(writer);
    reader.accept(cv, ClassReader.EXPAND_FRAMES);
    return writer.toByteArray();
  }

  /**
   * Creates a {@link ClassWriter} with {@code COMPUTE_FRAMES} that falls back to {@code
   * java/lang/Object} when {@code getCommonSuperClass} cannot resolve types — matches the ByteBuddy
   * production context where the {@link net.bytebuddy.pool.TypePool} handles unknown supertypes
   * gracefully rather than throwing.
   */
  private static ClassWriter safeComputeFramesWriter(ClassReader reader) {
    return new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
      @Override
      protected String getCommonSuperClass(String type1, String type2) {
        if (type1.equals(type2)) {
          return type1;
        }
        try {
          return super.getCommonSuperClass(type1, type2);
        } catch (Exception ignored) {
          return "java/lang/Object";
        }
      }
    };
  }

  // ---------------------------------------------------------------------------- fixture classes

  /** Three {@code synchronized} instance methods — dense rewriting target. */
  static class SyncMethodFixture {
    private int counter;

    synchronized int increment() {
      return ++counter;
    }

    synchronized int decrement() {
      return --counter;
    }

    synchronized int reset() {
      int old = counter;
      counter = 0;
      return old;
    }
  }

  /** Three {@code synchronized} blocks on a shared lock — realistic application pattern. */
  static class SyncBlockFixture {
    private final Object lock = new Object();
    private int[] data = new int[16];

    int read(int idx) {
      synchronized (lock) {
        return data[idx];
      }
    }

    void write(int idx, int value) {
      synchronized (lock) {
        data[idx] = value;
      }
    }

    int swap(int idx, int value) {
      synchronized (lock) {
        int old = data[idx];
        data[idx] = value;
        return old;
      }
    }
  }

  /** Plain class with no monitors — exercises the structural no-op path through the visitor. */
  static class NoSyncFixture {
    private int x;
    private int y;

    int compute(int a, int b) {
      x = a + b;
      y = a * b;
      return x ^ y;
    }

    int getX() {
      return x;
    }

    int getY() {
      return y;
    }
  }
}
