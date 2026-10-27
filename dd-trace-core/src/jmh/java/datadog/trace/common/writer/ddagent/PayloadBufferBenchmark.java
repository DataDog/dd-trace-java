package datadog.trace.common.writer.ddagent;

import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE;
import static datadog.trace.api.DDTags.RUNTIME_ID_TAG;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.collect.Lists;
import datadog.communication.serialization.ChunkedStreamingBuffer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.MessageFormatter;
import datadog.communication.serialization.StreamingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.DDSpanHelper;
import datadog.trace.core.TraceCollector;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Cost of serializing a trace into the trace writer's payload buffer: the v0.4 mapper formatting
 * one root span into a 5 MiB buffer, as {@code PayloadDispatcherImpl} does, with each full payload
 * handed to a consumer that discards it. Compares the single-array {@link FlushingBuffer} with the
 * {@link ChunkedStreamingBuffer}.
 *
 * <p>Read ns/op and, with {@code -prof gc}, {@code gc.alloc.rate.norm}. Results differ by JDK: on
 * JDK 8 heap {@code ByteBuffer} typed puts are byte-wise for both buffers. Run on 8, 17 and 21.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 2, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 2)
@SuppressForbidden
public class PayloadBufferBenchmark {

  private static final int BUFFER_SIZE = 5 << 20;

  @Param({"flushing", "chunked"})
  String buffer;

  private TraceMapper mapper;
  private MessageFormatter writer;
  private List<DDSpan> trace;

  @Setup(Level.Trial)
  public void init(Blackhole blackhole) {
    mapper = new TraceMapperV0_4();
    StreamingBuffer streamingBuffer;
    switch (buffer) {
      case "flushing":
        streamingBuffer =
            new FlushingBuffer(
                BUFFER_SIZE,
                (messageCount, body) -> {
                  blackhole.consume(body);
                  mapper.reset();
                });
        break;
      case "chunked":
        streamingBuffer =
            new ChunkedStreamingBuffer(
                BUFFER_SIZE,
                (messageCount, chunks) -> {
                  blackhole.consume(chunks);
                  mapper.reset();
                });
        break;
      default:
        throw new IllegalArgumentException("Illegal buffer type " + buffer + ".");
    }
    writer = new MsgPackWriter(streamingBuffer);

    Map<String, Object> tags = new HashMap<>();
    tags.put(RUNTIME_ID_TAG, "fdd790b3-4aeb-4517-9b84-cafcc0129c48");
    tags.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    tags.put("env", "prod");

    CoreTracer tracer =
        CoreTracer.builder()
            .strictTraceWrites(
                true) // Avoid any extra bookkeeping for traces since we write directly
            .build();
    DDTraceId traceId = DDTraceId.ONE;
    TraceCollector traceCollector = tracer.createTraceCollector(traceId);
    DDSpanContext rootContext =
        new DDSpanContext(
            traceId,
            2,
            DDSpanId.ZERO,
            null,
            "service",
            UTF8BytesString.create("operation"),
            UTF8BytesString.create("resource"),
            PrioritySampling.SAMPLER_KEEP,
            null,
            Collections.<String, String>emptyMap(),
            false,
            UTF8BytesString.create("type"),
            0,
            traceCollector,
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            null);
    DDSpanHelper.setAllTags(rootContext, tags);
    DDSpan root = DDSpanHelper.create("benchmark", System.currentTimeMillis() * 1000, rootContext);
    root.setResourceName(UTF8BytesString.create("benchmark"));
    trace = Lists.newArrayList(root);
  }

  @Benchmark
  public boolean formatTrace() {
    return writer.format(trace, mapper);
  }
}
