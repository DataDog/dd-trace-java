package datadog.trace.api.naming;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.core.BlackholeWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.TraceCounters;
import java.util.WeakHashMap;
import java.util.function.Supplier;
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
 * Benchmark (pinThreadServiceName) Mode Cnt Score Error Units
 * MessagingNamingBenchmark.complexSupplierServiceName false avgt 3 0.710 ± 0.040 us/op
 * MessagingNamingBenchmark.complexSupplierServiceName true avgt 3 0.718 ± 0.105 us/op
 * MessagingNamingBenchmark.constantServiceName false avgt 3 0.668 ± 0.024 us/op
 * MessagingNamingBenchmark.constantSupplierServiceName false avgt 3 0.666 ± 0.044 us/op
 */
@State(Scope.Benchmark)
@Warmup(iterations = 1, time = 15, timeUnit = SECONDS)
@Measurement(iterations = 3, time = 30, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class MessagingNamingBenchmark {

  CoreTracer tracer;

  WeakHashMap<ClassLoader, String> weakCache;

  private static final Supplier<String> constantSupplier = () -> "constant";

  private final Supplier<String> complexSupplier =
      () -> {
        String ret = weakCache.get(Thread.currentThread().getContextClassLoader());
        if (ret == null) {
          ret = Config.get().getServiceName();
        }
        return ret;
      };

  @Param({"false", "true"})
  boolean pinThreadServiceName;

  @Setup(Level.Iteration)
  public void init(Blackhole blackhole) {
    tracer =
        CoreTracer.builder()
            .writer(new BlackholeWriter(blackhole, new TraceCounters(), 0))
            .strictTraceWrites(false)
            .build();
    weakCache = new WeakHashMap<>();
    if (pinThreadServiceName) {
      weakCache.put(Thread.currentThread().getContextClassLoader(), constantSupplier.get());
    }
  }

  @Benchmark
  public void constantServiceName(Blackhole blackhole) {
    final AgentSpan span = tracer.startSpan("", "");
    span.setServiceName("constant");
    span.finish();
    blackhole.consume(span);
  }

  @Benchmark
  public void constantSupplierServiceName(Blackhole blackhole) {
    final AgentSpan span = tracer.startSpan("", "");
    span.setServiceName(constantSupplier.get());
    span.finish();
    blackhole.consume(span);
  }

  @Benchmark
  public void complexSupplierServiceName(Blackhole blackhole) {
    final AgentSpan span = tracer.startSpan("", "");
    span.setServiceName(complexSupplier.get());
    span.finish();
    blackhole.consume(span);
  }
}
