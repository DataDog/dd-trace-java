package datadog.trace.core.context;

import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.BlackholeWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.TraceCounters;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 15, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 30, timeUnit = SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(SECONDS)
@Fork(value = 1)
@Threads(8)
public class ShortLivedContextBenchmark {
  // CPU token count for Benchmark.consumeCPU()
  private static final long HUGE_CPU_LOAD = 10_000_000;
  private static final long MEDIUM_CPU_LOAD = 1_000_000;
  private static final long SMALL_CPU_LOAD = 1_000;
  private static final long NO_CPU_LOAD = 0;
  // Delays for Thread.sleep in microseconds
  private static final long HUGE_IO_WAIT = 1_000_000;
  private static final long MEDIUM_IO_WAIT = 1_000;
  private static final long SMALL_IO_WAIT = 200;
  private static final long NO_IO_WAIT = 0;

  private static final String INSTRUMENTATION = "benchmark";
  CoreTracer tracer;

  @Setup(Level.Iteration)
  public void setup(Blackhole blackhole) {
    this.tracer =
        CoreTracer.builder()
            .writer(new BlackholeWriter(blackhole, new TraceCounters(), 0))
            .strictTraceWrites(false)
            .build();
  }

  @Benchmark
  public void benchmarkTraceSegment() {
    makeSpan(
        "root",
        MEDIUM_CPU_LOAD,
        0,
        () -> {
          makeSpan("child1", HUGE_CPU_LOAD, 0);
          makeSpan("child2", SMALL_CPU_LOAD, 0);
          makeSpan(
              "child3",
              NO_CPU_LOAD,
              MEDIUM_IO_WAIT,
              () -> makeSpan("great-child3-1", MEDIUM_CPU_LOAD, 0));
          makeSpan("child4", NO_CPU_LOAD, NO_IO_WAIT);
        });
  }

  @Benchmark
  public void benchmarkContextSwap() {
    makeSpan(
        "root",
        MEDIUM_CPU_LOAD,
        0,
        () -> {
          CompletableFuture<?>[] futures =
              IntStream.range(0, 100)
                  .mapToObj(
                      spanId ->
                          CompletableFuture.runAsync(
                              () -> makeSpan("child" + spanId, SMALL_CPU_LOAD, 0)))
                  .toArray(CompletableFuture[]::new);
          CompletableFuture.allOf(futures).join();
        });
  }

  private void makeSpan(String spanName, long cpuLoad, long ioWait) {
    makeSpan(spanName, cpuLoad, ioWait, null);
  }

  private void makeSpan(String spanName, long CPU_LOAD, long ioWait, Runnable additionalWork) {
    AgentSpan span = tracer.startSpan(INSTRUMENTATION, spanName);
    try (ContextScope ignored = span.attach()) {
      if (CPU_LOAD > 0) {
        Blackhole.consumeCPU(CPU_LOAD);
      }
      if (ioWait > 0) {
        sleep(ioWait);
      }
      if (additionalWork != null) {
        additionalWork.run();
      }
    }
  }

  private void sleep(long micro) {
    try {
      long millis = micro / 1000;
      int nano = (int) (micro % 1000) * 1000;
      Thread.sleep(millis, nano);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
