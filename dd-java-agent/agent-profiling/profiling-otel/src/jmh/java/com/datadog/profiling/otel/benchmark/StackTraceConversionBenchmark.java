package com.datadog.profiling.otel.benchmark;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static org.openjdk.jmh.annotations.Mode.Throughput;

import com.datadog.profiling.otel.JfrToOtlpConverter;
import com.datadog.profiling.otel.jfr.JfrClass;
import com.datadog.profiling.otel.jfr.JfrMethod;
import com.datadog.profiling.otel.jfr.JfrStackFrame;
import com.datadog.profiling.otel.jfr.JfrStackTrace;
import java.lang.reflect.Method;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmarks for stack trace conversion performance.
 *
 * <p>Tests the conversion of JFR stack traces to OTLP Location/Function/Stack format with varying
 * stack depths and deduplication ratios.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Throughput)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
@Warmup(iterations = 3, time = 5)
@Measurement(iterations = 3, time = 5)
public class StackTraceConversionBenchmark {

  @Param({"5", "15", "30", "50"})
  int stackDepth;

  @Param({"1", "10", "100"})
  int uniqueStacks;

  private JfrStackTrace[] stackTraces;
  private JfrToOtlpConverter converter;

  // Use reflection to access the private convertStackTrace method
  private Method convertStackTraceMethod;

  @Setup(Level.Trial)
  public void setup() throws Exception {
    Random rnd = new Random(42);
    converter = new JfrToOtlpConverter();

    // Access private method for benchmark
    convertStackTraceMethod =
        JfrToOtlpConverter.class.getDeclaredMethod("convertStackTrace", JfrStackTrace.class);
    convertStackTraceMethod.setAccessible(true);

    // Generate unique stack traces
    stackTraces = new JfrStackTrace[uniqueStacks];
    for (int i = 0; i < uniqueStacks; i++) {
      stackTraces[i] = createMockStackTrace(stackDepth, i, rnd);
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    converter.reset();
  }

  @Benchmark
  public void convertStackTrace(Blackhole bh) throws Exception {
    int idx = ThreadLocalRandom.current().nextInt(stackTraces.length);
    Object result = convertStackTraceMethod.invoke(converter, stackTraces[idx]);
    bh.consume(result);
  }

  private JfrStackTrace createMockStackTrace(int depth, int variant, Random rnd) {
    JfrStackFrame[] frames = new JfrStackFrame[depth];
    for (int i = 0; i < depth; i++) {
      frames[i] = createMockFrame(i, variant, rnd);
    }
    return new MockStackTrace(frames);
  }

  private JfrStackFrame createMockFrame(int frameIdx, int variant, Random rnd) {
    String className = generateClassName(variant, frameIdx, rnd);
    String methodName = generateMethodName(variant, frameIdx, rnd);
    int lineNumber = 100 + frameIdx * 10 + variant;
    return new MockStackFrame(new MockMethod(methodName, new MockClass(className)), lineNumber);
  }

  private String generateClassName(int variant, int frameIdx, Random rnd) {
    String[] packages = {"com.example", "org.apache", "io.netty", "datadog.trace"};
    String[] classes = {"Handler", "Service", "Controller", "Manager", "Factory"};
    int pkgIdx = (variant + frameIdx) % packages.length;
    int clsIdx = (variant * 7 + frameIdx) % classes.length;
    return packages[pkgIdx] + "." + classes[clsIdx] + (variant % 10);
  }

  private String generateMethodName(int variant, int frameIdx, Random rnd) {
    String[] methods = {"process", "handle", "execute", "invoke", "run", "doWork"};
    int methodIdx = (variant * 3 + frameIdx) % methods.length;
    return methods[methodIdx] + (variant % 5);
  }

  // Mock implementations of JFR interfaces
  private static class MockStackTrace implements JfrStackTrace {
    private final JfrStackFrame[] frames;

    MockStackTrace(JfrStackFrame[] frames) {
      this.frames = frames;
    }

    @Override
    public JfrStackFrame[] frames() {
      return frames;
    }
  }

  private static class MockStackFrame implements JfrStackFrame {
    private final JfrMethod method;
    private final int lineNumber;

    MockStackFrame(JfrMethod method, int lineNumber) {
      this.method = method;
      this.lineNumber = lineNumber;
    }

    @Override
    public JfrMethod method() {
      return method;
    }

    @Override
    public int lineNumber() {
      return lineNumber;
    }
  }

  private static class MockMethod implements JfrMethod {
    private final String name;
    private final JfrClass type;

    MockMethod(String name, JfrClass type) {
      this.name = name;
      this.type = type;
    }

    @Override
    public JfrClass type() {
      return type;
    }

    @Override
    public String name() {
      return name;
    }
  }

  private static class MockClass implements JfrClass {
    private final String name;

    MockClass(String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }
  }
}
