package datadog.trace.instrumentation.kafka_clients;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.instrumentation.kafka_clients.TextMapInjectAdapter.SETTER;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import org.apache.kafka.common.header.internals.RecordHeaders;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Injecting a span's context into a new record's headers, as the producer does on each send, with a
 * default-configured {@link CoreTracer}.
 *
 * <p>{@code shape} sets which span each inject is for. {@code reused} injects one span every time,
 * so values cached on its ids (like the trace id's string forms) are only built once: a best case
 * for caching, closest to measuring just the writes to Kafka's {@link RecordHeaders}. {@code
 * newTrace} starts a new trace per inject, as a producer sending outside any request does. {@code
 * fanOut} starts a new child span of one long-lived trace per inject, as a request sending many
 * messages does, so trace-level values can be reused and span-level ones can't. {@code spanBaggage}
 * gives the span a baggage item, which both the Datadog and W3C codecs write as the same {@code
 * ot-baggage-} header, so the second write replaces the first.
 *
 * <p>Single-threaded: each call writes its own headers, with no shared state to contend on.
 *
 * <pre>
 *   ./gradlew :dd-java-agent:instrumentation:kafka:kafka-clients-0.11:jmh \
 *       -Pjmh.includes=KafkaInjectBenchmark -Pjmh.profilers=gc -PtestJvm=17
 * </pre>
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Threads(1)
@Fork(3)
public class KafkaInjectBenchmark {
  @Param({"reused", "newTrace", "fanOut"})
  String shape;

  @Param({"false", "true"})
  boolean spanBaggage;

  CoreTracer tracer;
  AgentSpan span;

  @Setup
  public void setup() {
    // Building the tracer registers its propagators as the default ones. The span only finishes at
    // teardown, so the writer never runs while measuring.
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
    AgentTracer.forceRegister(tracer);
    span = withBaggage(tracer.buildSpan("bench", "kafka.produce").start());
  }

  @TearDown
  public void tearDown() {
    span.finish();
    AgentTracer.forceRegister(AgentTracer.NOOP_TRACER);
    tracer.close();
  }

  @Benchmark
  public RecordHeaders inject() {
    RecordHeaders headers = new RecordHeaders();
    defaultPropagator().inject(spanToInject(), headers, SETTER);
    return headers;
  }

  // Spans started here are left unfinished, so no trace is written while measuring
  private AgentSpan spanToInject() {
    switch (shape) {
      case "newTrace":
        return withBaggage(tracer.buildSpan("bench", "kafka.produce").start());
      case "fanOut":
        return withBaggage(
            tracer.buildSpan("bench", "kafka.produce").asChildOf(span.spanContext()).start());
      default:
        return span;
    }
  }

  private AgentSpan withBaggage(AgentSpan started) {
    if (spanBaggage) {
      started.setBaggageItem("user.id", "42");
    }
    return started;
  }
}
