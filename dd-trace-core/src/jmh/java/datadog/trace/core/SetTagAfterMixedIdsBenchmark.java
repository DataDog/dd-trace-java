package datadog.trace.core;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import datadog.trace.api.KnownTags;
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
 * {@link SetTagBenchmark}'s constant-id cases, in a JVM that first sets many different intercepted
 * tags through the same {@code setTag} overload with an id that varies, as an application with
 * several integrations does.
 *
 * <p>That compiles {@code DDSpanContext.setTag} on its own with a profile that has seen every
 * handler. If the handler bodies landed in its compiled code, it would grow past C2's {@code
 * InlineSmallCode} and constant-id call sites would refuse to inline it ("already compiled into a
 * big method"), losing the folding {@link SetTagBenchmark} measures. Compare the two, and check
 * {@code -XX:+PrintInlining}.
 *
 * <p>{@code warmup} picks how the warm-up writes: {@code overwrite} sets the same tags on one span
 * over and over, so the store's overwrite path is hot; {@code freshSpans} sets each tag once on a
 * new span per round, as real spans mostly do; {@code plainHeavy} does the same with mostly plain
 * tags, so one handler dominates the setters' type profile, as in a real application.
 */
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 3)
public class SetTagAfterMixedIdsBenchmark {
  static final CoreTracer TRACER = CoreTracer.builder().build();

  private static final long[] MIXED_IDS = {
    KnownTags.SPAN_KIND_ID,
    KnownTags.HTTP_METHOD_ID,
    KnownTags.HTTP_URL_ID,
    KnownTags.HTTP_STATUS_CODE_ID,
    KnownTags.RESOURCE_NAME_ID,
    KnownTags.DB_STATEMENT_ID,
    KnownTags.ERROR_ID,
    KnownTags.SPAN_TYPE_ID,
    KnownTags.HTTP_ROUTE_ID
  };
  private static final String[] MIXED_VALUES = {
    "client",
    "GET",
    "http://localhost/users/1",
    "200",
    "GET /users/{id}",
    "SELECT 1",
    "false",
    "web",
    "/users/{id}"
  };

  /** Mostly plain tags, as real spans set: about 92% of these writes store with no routing. */
  private static final long[] PLAIN_HEAVY_IDS = {
    KnownTags.COMPONENT_ID,
    KnownTags.LANGUAGE_ID,
    KnownTags.DB_TYPE_ID,
    KnownTags.DB_INSTANCE_ID,
    KnownTags.DB_OPERATION_ID,
    KnownTags.DB_USER_ID,
    KnownTags.HTTP_ROUTE_ID,
    KnownTags.HTTP_USERAGENT_ID,
    KnownTags.HTTP_CLIENT_IP_ID,
    KnownTags.HTTP_HOSTNAME_ID,
    KnownTags.PEER_HOSTNAME_ID,
    KnownTags.SPAN_KIND_ID
  };

  private static final String[] PLAIN_HEAVY_VALUES = {
    "jdbc",
    "jvm",
    "postgresql",
    "users",
    "SELECT",
    "app",
    "/users/{id}",
    "curl/8",
    "10.0.0.1",
    "example.com",
    "db.example.com",
    "client"
  };
  private static final int WARMUP_ROUNDS = 100_000;

  @Param({"overwrite", "freshSpans", "plainHeavy"})
  public String warmup;

  private DDSpan span;

  @Setup
  public void setup() {
    boolean freshSpans = !"overwrite".equals(warmup);
    long[] ids = "plainHeavy".equals(warmup) ? PLAIN_HEAVY_IDS : MIXED_IDS;
    String[] values = "plainHeavy".equals(warmup) ? PLAIN_HEAVY_VALUES : MIXED_VALUES;
    DDSpan warmupSpan = (DDSpan) TRACER.startSpan("benchmark", "set.tag.warmup");
    for (int round = 0; round < WARMUP_ROUNDS; ++round) {
      if (freshSpans) {
        warmupSpan.finish();
        warmupSpan = (DDSpan) TRACER.startSpan("benchmark", "set.tag.warmup");
      }
      for (int i = 0; i < ids.length; ++i) {
        warmupSpan.setTag(ids[i], values[i]);
      }
    }
    warmupSpan.finish();
    span = (DDSpan) TRACER.startSpan("benchmark", "set.tag");
  }

  @TearDown
  public void tearDown() {
    span.finish();
  }

  @Benchmark
  public Object knownTag_byId() {
    return span.setTag(KnownTags.HTTP_ROUTE_ID, "/users/{id}");
  }

  @Benchmark
  public Object interceptedTag_byId() {
    return span.setTag(KnownTags.SPAN_KIND_ID, "client");
  }

  @Benchmark
  public Object consumedTag_byId() {
    return span.setTag(KnownTags.RESOURCE_NAME_ID, "GET /users/{id}");
  }
}
