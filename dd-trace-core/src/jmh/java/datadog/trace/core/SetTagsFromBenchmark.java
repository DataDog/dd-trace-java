package datadog.trace.core;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.KnownTags;
import datadog.trace.bootstrap.instrumentation.api.TagContributor;
import datadog.trace.bootstrap.instrumentation.api.TagsWriter;
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

/**
 * A decorator-style sweep of nine tags by constant id -- a database client span's -- set two ways:
 * {@code perTag}, nine {@code DDSpan.setTag} calls, each taking the span's tag lock unless C2
 * coarsens them; and {@code setTagsFrom}, one {@link TagContributor} that writes all nine through a
 * {@link TagsWriter} under one acquisition.
 *
 * <p>Run once more with {@code -jvmArgsAppend -XX:-EliminateLocks} to see how much of {@code
 * perTag}'s cost C2's lock coarsening already removes.
 */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 3)
public class SetTagsFromBenchmark {
  static final CoreTracer TRACER = CoreTracer.builder().build();

  /** A typed source that projects itself onto a span, as a {@code DbInfo} would. */
  static final class DbInfo implements TagContributor {
    final String type = "postgresql";
    final String instance = "users";
    final String user = "app";
    final String operation = "SELECT";
    final String host = "db.example.com";
    final int port = 5432;

    @Override
    public void addTo(TagsWriter writer) {
      writer.setTag(KnownTags.SPAN_KIND_ID, "client");
      writer.setTag(KnownTags.COMPONENT_ID, "jdbc");
      writer.setTag(KnownTags.DB_TYPE_ID, type);
      writer.setTag(KnownTags.DB_INSTANCE_ID, instance);
      writer.setTag(KnownTags.DB_USER_ID, user);
      writer.setTag(KnownTags.DB_OPERATION_ID, operation);
      writer.setTag(KnownTags.PEER_HOSTNAME_ID, host);
      writer.setTag(KnownTags.PEER_PORT_ID, port);
      writer.setTag(KnownTags.HTTP_ROUTE_ID, "/users/{id}");
    }
  }

  static final DbInfo DB_INFO = new DbInfo();

  private DDSpan span;

  @Setup
  public void setup() {
    span = (DDSpan) TRACER.startSpan("benchmark", "set.tags.from");
  }

  @TearDown
  public void tearDown() {
    span.finish();
  }

  @Benchmark
  public Object perTag() {
    DbInfo info = DB_INFO;
    DDSpan span = this.span;
    span.setTag(KnownTags.SPAN_KIND_ID, "client");
    span.setTag(KnownTags.COMPONENT_ID, "jdbc");
    span.setTag(KnownTags.DB_TYPE_ID, info.type);
    span.setTag(KnownTags.DB_INSTANCE_ID, info.instance);
    span.setTag(KnownTags.DB_USER_ID, info.user);
    span.setTag(KnownTags.DB_OPERATION_ID, info.operation);
    span.setTag(KnownTags.PEER_HOSTNAME_ID, info.host);
    span.setTag(KnownTags.PEER_PORT_ID, info.port);
    span.setTag(KnownTags.HTTP_ROUTE_ID, "/users/{id}");
    return span;
  }

  @Benchmark
  public Object setTagsFrom() {
    return span.setTagsFrom(DB_INFO);
  }
}
