package datadog.trace.common.writer.ddagent;

import static datadog.trace.api.DDTags.LANGUAGE_TAG_KEY;
import static datadog.trace.api.DDTags.LANGUAGE_TAG_VALUE;
import static datadog.trace.api.DDTags.RUNTIME_ID_TAG;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.google.common.collect.Lists;
import datadog.communication.serialization.StreamingBuffer;
import datadog.communication.serialization.Writable;
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
import datadog.trace.core.propagation.PropagationTags;
import de.thetaphi.forbiddenapis.SuppressForbidden;
import java.nio.ByteBuffer;
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

@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 120, timeUnit = SECONDS)
@Measurement(iterations = 3, time = 120, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
@SuppressForbidden
public class TraceMapperBenchmark {

  @Param({"v04", "v04:x-dth", "v05", "v05:x-dth"})
  String mapperName;

  private TraceMapper mapper;
  private Writable writable;
  private CoreTracer tracer;
  private List<DDSpan> trace;

  @Setup(Level.Trial)
  public void init(Blackhole blackhole) {
    PropagationTags propagationTags = null;
    String[] mapperAndFeatures = mapperName.split(":");
    switch (mapperAndFeatures[0]) {
      case "v04":
        mapper = new TraceMapperV0_4();
        break;
      case "v05":
        mapper = new TraceMapperV0_5();
        break;
      default:
        throw new IllegalArgumentException("Illegal mapper type " + mapperAndFeatures[0] + ".");
    }
    for (int i = 1; i < mapperAndFeatures.length; i++) {
      String feature = mapperAndFeatures[i];
      switch (feature) {
        case "x-dth":
          propagationTags =
              PropagationTags.factory()
                  .fromHeaderValue(
                      PropagationTags.HeaderType.DATADOG,
                      "_dd.p.anytag=value,_dd.p.dm=934086a686-4");
          break;
        default:
          throw new IllegalArgumentException("Unknown benchmark feature " + feature + ".");
      }
    }

    Map<String, Object> tags = new HashMap<>();
    tags.put(RUNTIME_ID_TAG, "fdd790b3-4aeb-4517-9b84-cafcc0129c48");
    tags.put(LANGUAGE_TAG_KEY, LANGUAGE_TAG_VALUE);
    tags.put("env", "prod");

    writable = new MsgPackWriter(new BlackholeBuffer(blackhole));

    tracer =
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
            propagationTags);
    DDSpanHelper.setAllTags(rootContext, tags);
    DDSpan root = DDSpanHelper.create("benchmark", System.currentTimeMillis() * 1000, rootContext);
    root.setResourceName(UTF8BytesString.create("benchmark"));
    trace = Lists.newArrayList(root);
  }

  @Benchmark
  public void mapTrace() {
    mapper.map(trace, writable);
  }

  public static final class BlackholeBuffer implements StreamingBuffer {
    private final Blackhole blackhole;

    public BlackholeBuffer(Blackhole blackhole) {
      this.blackhole = blackhole;
    }

    @Override
    public int capacity() {
      return 0;
    }

    @Override
    public boolean isDirty() {
      return false;
    }

    @Override
    public void mark() {}

    @Override
    public boolean flush() {
      return true;
    }

    @Override
    public void put(byte b) {
      blackhole.consume(b);
    }

    @Override
    public void putShort(short s) {
      blackhole.consume(s);
    }

    @Override
    public void putChar(char c) {
      blackhole.consume(c);
    }

    @Override
    public void putInt(int i) {
      blackhole.consume(i);
    }

    @Override
    public void putLong(long l) {
      blackhole.consume(l);
    }

    @Override
    public void putFloat(float f) {
      blackhole.consume(f);
    }

    @Override
    public void putDouble(double d) {
      blackhole.consume(d);
    }

    @Override
    public void put(byte[] bytes) {
      blackhole.consume(bytes);
    }

    @Override
    public void put(byte[] bytes, int offset, int length) {
      blackhole.consume(bytes);
    }

    @Override
    public void put(ByteBuffer buffer) {
      blackhole.consume(buffer);
    }

    @Override
    public void reset() {}
  }
}
