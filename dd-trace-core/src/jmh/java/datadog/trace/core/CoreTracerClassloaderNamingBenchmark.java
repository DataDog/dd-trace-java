package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.WeakHashMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Benchmark (simulateOverhead) Mode Cnt Score Error Units
 * CoreTracerClassloaderNamingBenchmark.benchSpanCreation false avgt 3 0.747 ± 0.040 us/op
 * CoreTracerClassloaderNamingBenchmark.benchSpanCreation true avgt 3 0.695 ± 0.106 us/op
 */
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 15, timeUnit = SECONDS)
@Measurement(iterations = 3, time = 30, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class CoreTracerClassloaderNamingBenchmark {
  CoreTracer tracer;

  WeakHashMap<ClassLoader, String> weakCache;

  @Param({"false", "true"})
  boolean simulateOverhead;

  @Setup(Level.Iteration)
  public void init(Blackhole blackhole) {
    tracer =
        CoreTracer.builder()
            .writer(new BlackholeWriter(blackhole, new TraceCounters(), 0))
            .strictTraceWrites(false)
            .build();
    weakCache = new WeakHashMap<>();
    weakCache.put(Thread.currentThread().getContextClassLoader(), "test");
  }

  @Benchmark
  public void benchSpanCreation(Blackhole blackhole) {
    final AgentSpan span = tracer.startSpan("", "");
    if (simulateOverhead) {
      // simulates an extra getContextClassLoader + a WeakHashMap.get
      weakCache.get(Thread.currentThread().getContextClassLoader());
    }
    span.finish();
    blackhole.consume(span);
  }
}
