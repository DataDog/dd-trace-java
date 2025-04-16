package datadog.smoketest.profiling;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ThreadLocalRandom;

public class GenerativeStackTraces {

  private final Tracer tracer;
  private final boolean useMethodHandles;
  private final boolean useCapturingLambdas;

  private static final MethodHandle METHOD1 = getMethodHandle(1);
  private static final MethodHandle METHOD2 = getMethodHandle(2);
  private static final MethodHandle METHOD3 = getMethodHandle(3);
  private static final MethodHandle METHOD4 = getMethodHandle(4);

  public GenerativeStackTraces(String mode) {
    this(GlobalTracer.get(), mode);
  }

  public GenerativeStackTraces(Tracer tracer, String mode) {
    this.tracer = tracer;
    this.useMethodHandles = "MethodHandles".equalsIgnoreCase(mode);
    this.useCapturingLambdas = "CapturingLambdas".equalsIgnoreCase(mode);
  }

  public static void main(String... args) throws Throwable {
    int depth = args.length >= 1 ? Integer.parseInt(args[0]) : 30;
    int iterations = args.length >= 2 ? Integer.parseInt(args[1]) : 1000;
    String mode = args.length >= 3 ? args[2] : "Raw";
    GenerativeStackTraces app = new GenerativeStackTraces(mode);
    for (int i = 0; i < iterations; i++) {
      app.selectRandom(0, depth);
    }
  }

  private void selectRandom(int depth, int maxDepth) throws Throwable {
    if (depth == maxDepth) {
      return;
    }
    switch (ThreadLocalRandom.current().nextInt(5)) {
      case 0:
        if (useMethodHandles) {
          METHOD1.invokeExact(this, depth + 1, maxDepth);
        } else if (useCapturingLambdas) {
          invoke(() -> method1(depth + 1, maxDepth));
        } else {
          method1(depth + 1, maxDepth);
        }
        break;
      case 1:
        if (useMethodHandles) {
          METHOD2.invokeExact(this, depth + 1, maxDepth);
        } else if (useCapturingLambdas) {
          invoke(() -> method2(depth + 1, maxDepth));
        } else {
          method2(depth + 1, maxDepth);
        }
        break;
      case 2:
        if (useMethodHandles) {
          METHOD3.invokeExact(this, depth + 1, maxDepth);
        } else if (useCapturingLambdas) {
          invoke(() -> method3(depth + 1, maxDepth));
        } else {
          method3(depth + 1, maxDepth);
        }
        break;
      case 3:
        if (useMethodHandles) {
          METHOD4.invokeExact(this, depth + 1, maxDepth);
        } else if (useCapturingLambdas) {
          invoke(() -> method4(depth + 1, maxDepth));
        } else {
          method4(depth + 1, maxDepth);
        }
        break;
      default:
        work();
    }
  }

  public void method1(int depth, int maxDepth) throws Throwable {
    Span span = tracer.buildSpan("method1").start();
    try (Scope scope = tracer.activateSpan(span)) {
      selectRandom(depth, maxDepth);
    }
    span.finish();
  }

  public void method2(int depth, int maxDepth) throws Throwable {
    Span span = tracer.buildSpan("method2").start();
    try (Scope scope = tracer.activateSpan(span)) {
      selectRandom(depth, maxDepth);
    }
    span.finish();
  }

  public void method3(int depth, int maxDepth) throws Throwable {
    Span span = tracer.buildSpan("method3").start();
    try (Scope scope = tracer.activateSpan(span)) {
      selectRandom(depth, maxDepth);
    }
    span.finish();
  }

  public void method4(int depth, int maxDepth) throws Throwable {
    Span span = tracer.buildSpan("method4").start();
    try (Scope scope = tracer.activateSpan(span)) {
      selectRandom(depth, maxDepth);
    }
    span.finish();
  }

  public void work() {
    long blackhole = 0;
    for (int i = 0; i < 1000; i++) {
      blackhole ^= Long.reverse((long) (Math.log(1L << i)));
    }
    if (blackhole == ThreadLocalRandom.current().nextLong()) {
      System.err.println(blackhole);
    }
  }

  @FunctionalInterface
  interface ThrowingRunnable {
    void run() throws Throwable;
  }

  private static void invoke(ThrowingRunnable runnable) throws Throwable {
    runnable.run();
  }

  private static MethodHandle getMethodHandle(int methodNumber) {
    try {
      return MethodHandles.publicLookup()
          .findVirtual(
              GenerativeStackTraces.class,
              "method" + methodNumber,
              MethodType.methodType(void.class, int.class, int.class));
    } catch (Throwable t) {
      return null;
    }
  }
}
