package datadog.trace.common.writer.ddagent;

import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.communication.serialization.ChunkedStreamingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.Writer;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.List;
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
 * Compares the trace serializers on the path the writer takes: each trace is formatted through the
 * mapper into a {@link MsgPackWriter} over a {@link ChunkedStreamingBuffer}, and on flush the
 * chunks become the mapper's {@link Payload}, which is written out before the mapper is reset. So
 * per-payload work (the v0.5 dictionary, the v1 string table) is part of every operation.
 *
 * <p>An operation serializes one payload of {@code tracesPerPayload} traces. Each trace is a web
 * request root with a database, an HTTP client and an internal child span, built through the
 * tracer, with its own {@code http.url} value instances, as each request makes them: per-payload
 * deduplication that only works for repeated instances, rather than equal values, shows up here.
 * Several payloads rotate so the JIT doesn't specialize on one.
 *
 * <p>{@code cache} chooses whether the serialized values are fresh. Some value types cache their
 * rendered form ({@code LazyUrl} its string, {@code UTF8BytesString} its encoding), so serializing
 * the same spans again skips that work. {@code cold} gives the next payload's spans new {@code
 * http.url} values before each operation, outside the measured time, so each URL is serialized for
 * the first time, as in production; {@code warm} keeps the values and leaves the cost of first use
 * out. Cold refreshes per invocation, so with {@code tracesPerPayload=1} JMH's per-invocation
 * overhead is a large part of the timing: compare cold results at 100 and 1000 traces. And {@code
 * -prof gc} counts what the refresh allocates (the new URL values, as each request would make them)
 * along with what serialization does.
 *
 * <p>{@code urlType} is the {@code http.url} value's type ({@code String}, {@code UTF8BytesString},
 * {@code URIUtils.LazyUrl}, or a {@code StringBuilder} as a CharSequence that neither caches its
 * string nor compares by content); {@code urls} chooses whether the traces share one URL or each
 * have their own. Setup fails if the tracer hands the URL to the serializer as a different type
 * than asked for, since the comparison would silently measure something else.
 *
 * <p>Payload size is the other half of a serializer comparison: setup prints the bytes written per
 * span for each configuration. Serialization runs on one thread, so the benchmark does too. Use
 * {@code -prof gc} for allocation.
 *
 * <p>No Agent runs, so the fork disables the tracer's remote config, telemetry, health and client
 * stats, and keeps logging at warn: the bundled logback.xml logs at debug, and some serializers log
 * per value at that level. Agent feature discovery still retries on the tracer's scheduler thread,
 * off the measured one; each attempt fails fast.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Warmup(iterations = 3, time = 10, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 10, timeUnit = SECONDS)
@Fork(
    value = 3,
    // No Agent runs here: keep the tracer from polling one in the background during measurement.
    jvmArgsAppend = {
      "-Ddd.remote_configuration.enabled=false",
      "-Ddd.instrumentation.telemetry.enabled=false",
      "-Ddd.trace.health.metrics.enabled=false",
      "-Ddd.trace.tracer.metrics.enabled=false",
      "-Ddd.trace.startup.logs=false",
      "-DTEST_LOG_LEVEL=warn" // the bundled logback.xml defaults to debug
    })
@Threads(1)
public class TraceSerializerBenchmark {
  private static final int PAYLOADS = 8; // a power of two, for the rotation mask
  private static final int SPANS_PER_TRACE = 4;

  @Param({"v04", "v05", "v1"})
  String format;

  @Param({"1", "100", "1000"})
  int tracesPerPayload;

  @Param({"string", "utf8", "lazyurl", "stringbuilder"})
  String urlType;

  @Param({"same", "distinct"})
  String urls;

  @Param({"cold", "warm"})
  String cache;

  private TraceMapper mapper;
  private MsgPackWriter packer;
  private final CountingChannel channel = new CountingChannel();
  private List<List<List<DDSpan>>> payloads;
  private List<List<UrlTag>> urlTags; // per payload: the http.url tags a cold refresh replaces
  private int next;
  private CoreTracer tracer;
  private CollectingWriter collected;

  @Setup(Level.Trial)
  public void setup() {
    mapper = newMapper(format);
    packer =
        new MsgPackWriter(new ChunkedStreamingBuffer(mapper.messageBufferSize(), this::dispatch));

    collected = new CollectingWriter();
    tracer = CoreTracer.builder().writer(collected).strictTraceWrites(true).build();
    payloads = new ArrayList<>(PAYLOADS);
    urlTags = new ArrayList<>(PAYLOADS);
    for (int p = 0; p < PAYLOADS; p++) {
      List<UrlTag> tags = new ArrayList<>();
      for (int t = 0; t < tracesPerPayload; t++) {
        buildTrace(t, tags);
      }
      payloads.add(collected.drain());
      urlTags.add(tags);
    }
    checkUrlType(payloads.get(0).get(0));
    refreshUrls(urlTags.get(0));
    checkUrlType(payloads.get(0).get(0)); // a refreshed value must keep its type, too

    long before = channel.bytes;
    serializePayload();
    long bytes = channel.bytes - before;
    System.out.printf(
        "%n[TraceSerializerBenchmark] %s, %d traces, %s %s urls: %d bytes, %.1f bytes/span%n",
        format,
        tracesPerPayload,
        urls,
        urlType,
        bytes,
        (double) bytes / (tracesPerPayload * SPANS_PER_TRACE));
  }

  /**
   * For {@code cold}, gives the spans the next operation serializes new {@code http.url} values.
   */
  @Setup(Level.Invocation)
  public void refresh() {
    if ("cold".equals(cache)) {
      refreshUrls(urlTags.get(next & (PAYLOADS - 1)));
    }
  }

  private void refreshUrls(List<UrlTag> tags) {
    for (UrlTag tag : tags) {
      tag.span.spanContext().setTag(Tags.HTTP_URL, url(tag.host, tag.port, tag.path));
    }
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    tracer.close();
  }

  /** Serializes one payload and returns the running byte count, so the work can't be elided. */
  @Benchmark
  public long serializePayload() {
    for (List<DDSpan> trace : payloads.get(next++ & (PAYLOADS - 1))) {
      if (!packer.format(trace, mapper)) {
        throw new IllegalStateException("trace did not fit in the " + format + " buffer");
      }
    }
    packer.flush();
    return channel.bytes;
  }

  private void dispatch(int messageCount, List<ByteBuffer> chunks) {
    Payload payload = mapper.newPayload().withBody(messageCount, chunks);
    try {
      payload.writeTo(channel);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    mapper.reset();
  }

  private static TraceMapper newMapper(String format) {
    switch (format) {
      case "v04":
        return new TraceMapperV0_4();
      case "v05":
        return new TraceMapperV0_5();
      case "v1":
        return new TraceMapperV1();
      default:
        throw new IllegalArgumentException("Unknown format " + format);
    }
  }

  private void buildTrace(int index, List<UrlTag> urlTags) {
    String path = "distinct".equals(urls) ? "/owners/" + index : "/owners/1";
    AgentSpan root = tracer.buildSpan("benchmark", "servlet.request").withSpanType("web").start();
    root.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
    root.setTag(Tags.COMPONENT, "tomcat-server");
    root.setTag(Tags.HTTP_METHOD, "GET");
    root.setTag(Tags.HTTP_URL, url("petclinic", 8080, path));
    urlTags.add(new UrlTag((DDSpan) root, "petclinic", 8080, path));
    root.setTag(Tags.HTTP_ROUTE, "/owners/{ownerId}");
    root.setResourceName("GET /owners/{ownerId}");
    root.setHttpStatusCode(200);

    AgentSpan handler =
        tracer.buildSpan("benchmark", "spring.handler").asChildOf(root.spanContext()).start();
    handler.setResourceName("OwnerController.showOwner");
    handler.setTag(Tags.COMPONENT, "spring-web-controller");

    AgentSpan query =
        tracer
            .buildSpan("benchmark", "postgresql.query")
            .withSpanType("sql")
            .asChildOf(handler.spanContext())
            .start();
    query.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    query.setTag(Tags.COMPONENT, "java-jdbc-prepared_statement");
    query.setTag(Tags.DB_TYPE, "postgresql");
    query.setTag(Tags.DB_INSTANCE, "petclinic");
    query.setTag(Tags.PEER_HOSTNAME, "db.internal");
    query.setResourceName(
        "SELECT owner.id, owner.first_name, owner.last_name FROM owners owner WHERE owner.id = ?");
    query.finish();

    AgentSpan client =
        tracer
            .buildSpan("benchmark", "http.request")
            .withSpanType("http")
            .asChildOf(handler.spanContext())
            .start();
    client.setTag(Tags.SPAN_KIND, Tags.SPAN_KIND_CLIENT);
    client.setTag(Tags.COMPONENT, "okhttp");
    client.setTag(Tags.HTTP_METHOD, "GET");
    client.setTag(Tags.HTTP_URL, url("visits", 9090, path + "/visits"));
    urlTags.add(new UrlTag((DDSpan) client, "visits", 9090, path + "/visits"));
    client.setTag(Tags.PEER_HOSTNAME, "visits");
    client.setResourceName("GET /owners/?/visits");
    client.setHttpStatusCode(200);
    client.finish();

    handler.finish();
    root.finish();
  }

  /** A fresh {@code http.url} value of the requested type, as each request makes its own. */
  private CharSequence url(String host, int port, String path) {
    switch (urlType) {
      case "string":
        return new StringBuilder("http://")
            .append(host)
            .append(':')
            .append(port)
            .append(path)
            .toString();
      case "utf8":
        return UTF8BytesString.create("http://" + host + ":" + port + path);
      case "lazyurl":
        return URIUtils.lazyValidURL("http", host, port, path);
      case "stringbuilder":
        return new StringBuilder("http://").append(host).append(':').append(port).append(path);
      default:
        throw new IllegalArgumentException("Unknown urlType " + urlType);
    }
  }

  /**
   * Fails if the tracer didn't keep {@code http.url} as the type the configuration asked for. Reads
   * the stored value, which is what the serializers get: {@code getTag} converts {@code http.url}
   * to a {@code String} for API callers, so it can't tell.
   */
  private void checkUrlType(List<DDSpan> trace) {
    Class<?> expected = url("petclinic", 8080, "/").getClass();
    for (DDSpan span : trace) {
      Object url = span.spanContext().unsafeGetTag(Tags.HTTP_URL);
      if (url != null) {
        if (!expected.isInstance(url)) {
          throw new IllegalStateException(
              "http.url reaches the serializer as "
                  + url.getClass().getName()
                  + ", not "
                  + expected.getName()
                  + ": the "
                  + urlType
                  + " configuration would measure the wrong type");
        }
        return;
      }
    }
    throw new IllegalStateException("no span in the trace has http.url");
  }

  /** A span's {@code http.url} tag, with what it takes to make a fresh value for it. */
  private static final class UrlTag {
    final DDSpan span;
    final String host;
    final int port;
    final String path;

    UrlTag(DDSpan span, String host, int port, String path) {
      this.span = span;
      this.host = host;
      this.port = port;
      this.path = path;
    }
  }

  /** Collects finished traces, so payloads are built from what the tracer actually hands over. */
  private static final class CollectingWriter implements Writer {
    private final List<List<DDSpan>> traces = new ArrayList<>();

    @Override
    public void write(List<DDSpan> trace) {
      if (trace.size() != SPANS_PER_TRACE) {
        throw new IllegalStateException(
            "expected " + SPANS_PER_TRACE + " spans, got " + trace.size());
      }
      traces.add(trace);
    }

    List<List<DDSpan>> drain() {
      List<List<DDSpan>> drained = new ArrayList<>(traces);
      traces.clear();
      return drained;
    }

    @Override
    public void start() {}

    @Override
    public boolean flush() {
      return true;
    }

    @Override
    public void close() {}

    @Override
    public void incrementDropCounts(int spanCount) {}
  }

  /** Counts what a payload writes, standing in for the agent connection. */
  private static final class CountingChannel implements WritableByteChannel {
    long bytes;

    @Override
    public int write(ByteBuffer src) {
      int remaining = src.remaining();
      src.position(src.limit());
      bytes += remaining;
      return remaining;
    }

    @Override
    public boolean isOpen() {
      return true;
    }

    @Override
    public void close() {}
  }
}
