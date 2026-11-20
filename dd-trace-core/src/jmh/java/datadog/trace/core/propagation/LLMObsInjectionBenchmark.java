package datadog.trace.core.propagation;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.Config;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.api.llmobs.LLMObsInternal;
import datadog.trace.api.llmobs.LLMObsPropagationValues;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpanContext;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import javax.annotation.ParametersAreNonnullByDefault;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Injects one span context with an active LLM Observability scope, so both {@code x-datadog-tags}
 * and the {@code tracestate} {@code dd=} member carry the LLMObs propagation tags.
 *
 * <p>Each injection moves to the next entry in a pool of {@value #POOL_SIZE} distinct LLMObs trace
 * and span ids, far more than the 128-entry tag value cache holds, as a service creating new LLMObs
 * spans would. A fixed set of ids would hit that cache on every injection and hide what per-span
 * ids cost. The ML app, sample rate and sampling decision repeat across the pool, as they do in
 * practice. Both codecs of one injection see the same entry, as they would for one span.
 *
 * <p>The cursor into the pool is thread-local because the propagation source is a single global.
 * That lookup costs the same on any revision being compared.
 *
 * <p>Run with {@code -prof gc}; {@code gc.alloc.rate.norm} is the number to compare.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 10, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Threads(8)
@Fork(value = 3, jvmArgsAppend = "-DTEST_LOG_LEVEL=warn")
public class LLMObsInjectionBenchmark {
  static final int POOL_SIZE = 4096;

  @Param({"datadog,tracecontext", "datadog", "tracecontext"})
  String styles;

  private static final LLMObsPropagationValues[] POOL = new LLMObsPropagationValues[POOL_SIZE];

  private static final ThreadLocal<int[]> CURSOR = ThreadLocal.withInitial(() -> new int[1]);

  CoreTracer tracer;
  HttpCodec.Injector injector;
  AgentSpan span;
  DDSpanContext spanContext;

  @Setup(Level.Trial)
  public void setUp() {
    Random random = new Random(42);
    for (int i = 0; i < POOL_SIZE; i++) {
      POOL[i] =
          new LLMObsPropagationValues(
              Long.toUnsignedString(random.nextLong()),
              "llmobs-bench-app",
              null,
              null,
              null,
              Long.toUnsignedString(random.nextLong()),
              "1.0",
              "1");
    }
    LLMObsInternal.setPropagationSource(context -> POOL[CURSOR.get()[0]]);

    Set<TracePropagationStyle> injectStyles = EnumSet.noneOf(TracePropagationStyle.class);
    for (String style : styles.split(",")) {
      injectStyles.add(TracePropagationStyle.valueOfDisplayName(style));
    }
    injector = HttpCodec.createInjector(Config.get(), injectStyles, Collections.emptyMap());

    tracer = CoreTracer.builder().writer(new ListWriter()).strictTraceWrites(false).build();
    span = tracer.buildSpan("llmobs-bench", "operation").start();
    spanContext = (DDSpanContext) span.spanContext();
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    LLMObsInternal.setPropagationSource(null);
    span.finish();
    tracer.close();
  }

  @State(Scope.Thread)
  public static class Carrier {
    final Map<String, String> headers = new HashMap<>();

    /** This thread's cursor, which the propagation source reads. */
    int[] cursor;

    @Setup(Level.Trial)
    public void setUp() {
      cursor = CURSOR.get();
    }
  }

  @Benchmark
  public Map<String, String> inject(Carrier carrier) {
    int[] cursor = carrier.cursor;
    cursor[0] = (cursor[0] + 1) & (POOL_SIZE - 1);
    injector.inject(spanContext, carrier.headers, MAP_SETTER);
    return carrier.headers;
  }

  private static final CarrierSetter<Map<String, String>> MAP_SETTER = new MapSetter();

  @ParametersAreNonnullByDefault
  private static final class MapSetter implements CarrierSetter<Map<String, String>> {
    @Override
    public void set(Map<String, String> carrier, String key, String value) {
      carrier.put(key, value);
    }
  }
}
