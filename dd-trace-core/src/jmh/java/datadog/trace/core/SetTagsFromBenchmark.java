package datadog.trace.core;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.SpanData;
import datadog.trace.bootstrap.instrumentation.api.TagContributor;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Measures {@code DDSpan.setTagsFrom}, which takes the span's tag lock once for a whole batch,
 * against the same tags set one {@code setTag} call (and one lock) at a time.
 *
 * <p>The batch is a jdbc span's nine tags: eight known tags by id, among them an int ({@code
 * peer.port}) and an intercepted tag ({@code span.kind}), and one custom tag. They come from a
 * {@code DbInfo}-style POJO, the case {@link TagContributor} is for, or through a {@link
 * TagExtractor}.
 *
 * <ul>
 *   <li>{@code reused_*}: the tags overwritten on one live span, so only the writes are measured.
 *   <li>{@code fresh_*}: a span started, tagged and finished per op, so the batch's lock saving is
 *       seen next to a span's fixed cost. Its writer records each span's size, as serialization
 *       would, so the dense store's sizing hints converge.
 * </ul>
 *
 * <p>Run with {@code -prof gc} to check that the batch's short-lived {@code SpanData} is
 * scalar-replaced: the batched arms should allocate no more than the one-by-one arms.
 */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 3, jvmArgsAppend = "-DTEST_LOG_LEVEL=warn")
public class SetTagsFromBenchmark {
  /** A jdbc span's connection facts, contributed as tags: the shape TagContributor is for. */
  static final class DbInfo implements TagContributor {
    final String type = "postgresql";
    final String pool = "HikariPool-1";
    final String user = "app";
    final String instance = "petclinic";
    final String host = "db.internal";
    final int port = 54321; // outside Integer's box cache, like a real port
    final String operation = "SELECT";
    final String statement = "SELECT * FROM owners WHERE id = ?";

    @Override
    public void addTo(SpanData span) {
      span.setTag(KnownTags.SPAN_KIND_ID, Tags.SPAN_KIND_CLIENT);
      span.setTag(KnownTags.COMPONENT_ID, "java-jdbc-prepared_statement");
      span.setTag(KnownTags.DB_TYPE_ID, type);
      span.setTag(KnownTags.DB_POOL_NAME_ID, pool);
      span.setTag(KnownTags.DB_USER_ID, user);
      span.setTag(KnownTags.DB_INSTANCE_ID, instance);
      span.setTag(KnownTags.PEER_HOSTNAME_ID, host);
      span.setTag(KnownTags.PEER_PORT_ID, port);
      span.setTag(KnownTags.DB_OPERATION_ID, operation);
      span.setTag("db.statement.custom", statement);
    }
  }

  /** The same writes from a foreign source, through an extractor held in a constant. */
  static final class DbInfoExtractor implements TagExtractor<DbInfo> {
    static final DbInfoExtractor INSTANCE = new DbInfoExtractor();

    @Override
    public void extract(DbInfo info, SpanData span) {
      info.addTo(span);
    }
  }

  static final DbInfo DB_INFO = new DbInfo();

  private CoreTracer tracer;
  private DDSpan span;

  @Setup
  public void setup(Blackhole blackhole) {
    tracer =
        CoreTracer.builder().writer(new TraceShapeBenchmark.SizeRecordingWriter(blackhole)).build();
    span = (DDSpan) tracer.startSpan("java-jdbc", "database.query");
  }

  @TearDown
  public void tearDown() {
    span.finish();
    tracer.close();
  }

  /** The batch one setTag at a time: DDSpan is a SpanData too, so the same addTo applies. */
  private static void oneByOne(DDSpan span) {
    DB_INFO.addTo(span);
  }

  @Benchmark
  public Object reused_oneByOne() {
    oneByOne(span);
    return span;
  }

  @Benchmark
  public Object reused_contributor() {
    return span.setTagsFrom(DB_INFO);
  }

  @Benchmark
  public Object reused_extractor() {
    return span.setTagsFrom(DB_INFO, DbInfoExtractor.INSTANCE);
  }

  @Benchmark
  public Object fresh_oneByOne() {
    DDSpan fresh = (DDSpan) tracer.startSpan("java-jdbc", "database.query");
    oneByOne(fresh);
    fresh.finish();
    return fresh;
  }

  @Benchmark
  public Object fresh_contributor() {
    DDSpan fresh = (DDSpan) tracer.startSpan("java-jdbc", "database.query");
    fresh.setTagsFrom(DB_INFO);
    fresh.finish();
    return fresh;
  }

  @Benchmark
  public Object fresh_extractor() {
    DDSpan fresh = (DDSpan) tracer.startSpan("java-jdbc", "database.query");
    fresh.setTagsFrom(DB_INFO, DbInfoExtractor.INSTANCE);
    fresh.finish();
    return fresh;
  }
}
