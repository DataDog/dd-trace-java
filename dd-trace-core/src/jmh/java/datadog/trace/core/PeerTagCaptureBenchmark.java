package datadog.trace.core;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.TagLookup;
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
import org.openjdk.jmh.annotations.Warmup;

/**
 * Client-side stats' peer-tag capture, as the aggregator does it on every published span: the
 * per-name read (one {@code unsafeGetTag} per advertised peer tag) against the bulk read over a
 * prebuilt {@link TagLookup} ({@code mayHaveAnyTag}, then {@code unsafeGetTagStrings}). Each arm
 * replicates the capture code exactly, allocation included.
 *
 * <p>Span shapes: a Kafka consume span (a few tags, no peer tags), a JDBC client span (several peer
 * tags) and an HTTP server span (no peer tags). This class runs on the default (hash) tag store;
 * {@link Dense} runs the same arms with the dense store on.
 */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 3)
public class PeerTagCaptureBenchmark {
  static final CoreTracer TRACER = CoreTracer.builder().build();

  /** A peer-tag set the size of what the agent advertises today (43), known and custom names. */
  static final String[] PEER_TAGS = {
    "_dd.base_service",
    "peer.service",
    "peer.hostname",
    "out.host",
    "db.instance",
    "db.system",
    "db.name",
    "network.destination.name",
    "network.destination.ip",
    "server.address",
    "rpc.service",
    "rpc.system",
    "grpc.host",
    "http.host",
    "hostname",
    "messaging.destination",
    "messaging.destination.name",
    "messaging.system",
    "messaging.kafka.bootstrap.servers",
    "kafka.bootstrap.servers",
    "topicname",
    "aws.queue.name",
    "queuename",
    "aws.s3.bucket",
    "bucketname",
    "aws.dynamodb.table_name",
    "tablename",
    "aws.sns.topic_name",
    "aws.kinesis.stream_name",
    "streamname",
    "aws.lambda.function_name",
    "functionname",
    "aws.eventbridge.rule_name",
    "rulename",
    "aws.sfn.state_machine_name",
    "statemachinename",
    "cassandra.keyspace",
    "cassandra.cluster",
    "mongodb.db",
    "elasticsearch.index",
    "couchbase.bucket",
    "active_record.db.vendor",
    "msmq.queue.path"
  };

  static final TagLookup LOOKUP = TagLookup.of(PEER_TAGS);

  @Param({"kafkaConsume", "jdbcClient", "httpServer"})
  public String shape;

  private DDSpan span;

  @Setup
  public void setup() {
    span = (DDSpan) TRACER.startSpan("benchmark", "peer.capture");
    switch (shape) {
      case "kafkaConsume":
        span.setTag("span.kind", "consumer");
        span.setTag("component", "java-kafka");
        span.setTag("kafka.group", "bench-group");
        span.setTag("kafka.partition", 0);
        span.setTag("kafka.offset", 123456L);
        break;
      case "jdbcClient":
        span.setTag("span.kind", "client");
        span.setTag("component", "java-jdbc-statement");
        span.setTag("db.type", "postgresql");
        span.setTag("db.system", "postgresql");
        span.setTag("db.instance", "petclinic");
        span.setTag("db.user", "app");
        span.setTag("peer.hostname", "db.example");
        span.setTag("peer.port", 5432);
        span.setTag("peer.service", "postgres");
        span.setTag("_dd.base_service", "petclinic");
        break;
      case "httpServer":
        span.setTag("span.kind", "server");
        span.setTag("component", "tomcat-server");
        span.setTag("http.method", "GET");
        span.setTag("http.route", "/owners");
        span.setTag("http.status_code", 200);
        span.setTag("http.url", "http://localhost:8080/owners?lastName=");
        break;
      default:
        throw new IllegalArgumentException(shape);
    }
  }

  @TearDown
  public void tearDown() {
    span.finish();
  }

  /** The capture before TagLookup: a lookup per name, allocating on the first hit. */
  @Benchmark
  public String[] perName() {
    String[] names = PEER_TAGS;
    int n = names.length;
    String[] values = null;
    for (int i = 0; i < n; i++) {
      Object v = span.unsafeGetTag(names[i]);
      if (v != null) {
        if (values == null) {
          values = new String[n];
        }
        values[i] = v.toString();
      }
    }
    return values;
  }

  /** The capture with TagLookup: one mask pre-check, then one bulk read. */
  @Benchmark
  public String[] bulk() {
    TagLookup lookup = LOOKUP;
    int n = lookup.size();
    if (n == 0 || !span.mayHaveAnyTag(lookup)) {
      return null;
    }
    String[] values = new String[n];
    return span.unsafeGetTagStrings(lookup, values) == 0 ? null : values;
  }

  /** The same arms with the dense tag store on. */
  @Fork(
      value = 3,
      jvmArgsAppend = {"-Ddd.trace.experimental.dense.tags.enabled=true"})
  public static class Dense extends PeerTagCaptureBenchmark {}
}
