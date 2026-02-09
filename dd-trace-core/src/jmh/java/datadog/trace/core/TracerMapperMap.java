package datadog.trace.core;

import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.LoggingWriter;
import datadog.trace.common.writer.ddagent.TraceMapperV0_4;
import datadog.trace.common.writer.ddagent.TraceMapperV0_5;
import datadog.trace.common.writer.ddagent.TraceMapperV1;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
public class TracerMapperMap {
  private static final int SPAN_COUNT = 1000;

  private static final TraceMapperV0_4 mapperV4 = new TraceMapperV0_4();
  private static final TraceMapperV0_5 mapperV5 = new TraceMapperV0_5();
  private static final TraceMapperV1 mapperV1 = new TraceMapperV1();

  private static final CoreTracer tracer =
      CoreTracer.builder().writer(new LoggingWriter()).strictTraceWrites(true).build();

  private final List<DDSpan> spans = new ArrayList<>(SPAN_COUNT);
  private final List<DDSpan> enrichedSpans = new ArrayList<>(SPAN_COUNT);
  private final List<DDSpan> spansWithOrigin = new ArrayList<>(SPAN_COUNT);
  private final List<DDSpan> enrichedSpansWithOrigin = new ArrayList<>(SPAN_COUNT);

  private MsgPackWriter writer;

  @Setup(Level.Trial)
  public void init(Blackhole blackhole) throws Exception {
    writer = new MsgPackWriter(new BlackholeBuffer(blackhole));

    for (int i = 1; i <= SPAN_COUNT; i++) {
      spans.add(createSpanWithOrigin(i, null));
      enrichedSpans.add(createEnrichedSpanWithOrigin(i, null));
      spansWithOrigin.add(createSpanWithOrigin(i, "some-origin"));
      enrichedSpansWithOrigin.add(createEnrichedSpanWithOrigin(i, "some-origin"));
    }
  }

  @Benchmark
  public void mapTracesV4() {
    mapperV4.map(spans, writer);
  }

  @Benchmark
  public void mapEnrichedTracesV4() {
    mapperV4.map(enrichedSpans, writer);
  }

  @Benchmark
  public void mapTracesWithOriginV4() {
    mapperV4.map(spansWithOrigin, writer);
  }

  @Benchmark
  public void mapEnrichedTracesWithOriginV4() {
    mapperV4.map(enrichedSpansWithOrigin, writer);
  }

  @Benchmark
  public void mapTracesV5() {
    mapperV5.map(spans, writer);
  }

  @Benchmark
  public void mapEnrichedTracesV5() {
    mapperV5.map(enrichedSpans, writer);
  }

  @Benchmark
  public void mapTracesWithOriginV5() {
    mapperV5.map(spansWithOrigin, writer);
  }

  @Benchmark
  public void mapEnrichedTracesWithOriginV5() {
    mapperV5.map(enrichedSpansWithOrigin, writer);
  }

  @Benchmark
  public void mapTracesV1() {
    mapperV1.map(spans, writer);
  }

  @Benchmark
  public void mapEnrichedTracesV1() {
    mapperV1.map(enrichedSpans, writer);
  }

  @Benchmark
  public void mapTracesWithOriginV1() {
    mapperV1.map(spansWithOrigin, writer);
  }

  @Benchmark
  public void mapEnrichedTracesWithOriginV1() {
    mapperV1.map(enrichedSpansWithOrigin, writer);
  }

  private DDSpan createEnrichedSpanWithOrigin(int iter, final String origin) {
    final DDSpan span = createSpanWithOrigin(iter, origin);
    span.setTag("some-tag-key", "some-tag-value");
    span.setMetric("some-metric-key", 1.0);
    return span;
  }

  private DDSpan createSpanWithOrigin(int iter, final String origin) {
    final DDTraceId traceId = DDTraceId.from(iter);
    final TraceCollector traceCollector = tracer.createTraceCollector(traceId);
    return DDSpan.create(
        "benchmark",
        System.currentTimeMillis() * 1000,
        new DDSpanContext(
            traceId,
            1000 + iter,
            DDSpanId.ZERO,
            null,
            "service",
            "operation",
            "resource",
            PrioritySampling.SAMPLER_KEEP,
            origin,
            Collections.<String, String>emptyMap(),
            false,
            "type",
            0,
            traceCollector,
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            null),
        null);
  }
}
