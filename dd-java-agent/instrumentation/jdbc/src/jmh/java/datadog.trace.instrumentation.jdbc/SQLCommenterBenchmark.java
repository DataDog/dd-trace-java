package datadog.trace.instrumentation.jdbc;

import static datadog.trace.instrumentation.jdbc.SQLCommenter.DATABASE_SERVICE;
import static datadog.trace.instrumentation.jdbc.SQLCommenter.DD_ENV;
import static datadog.trace.instrumentation.jdbc.SQLCommenter.DD_VERSION;
import static datadog.trace.instrumentation.jdbc.SQLCommenter.PARENT_SERVICE;
import static datadog.trace.instrumentation.jdbc.SQLCommenter.TRACEPARENT;

import datadog.trace.api.DDTraceId;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class SQLCommenterBenchmark {

  private static final SortedMap<String, Object> TAGS = new TreeMap<>();
  private static final long spanId = 9876543210L;
  private static final DDTraceId traceId = DDTraceId.from(Long.MAX_VALUE);
  private static final Integer samplingPriority = 1;

  static {
    TAGS.put(PARENT_SERVICE, "my-service");
    TAGS.put(DATABASE_SERVICE, "my-db-service");
    TAGS.put(DD_ENV, "test");
    TAGS.put(DD_VERSION, "version-00");
    TAGS.put(TRACEPARENT, "00-00000000000000007fffffffffffffff-000000024cb016ea-00");
  }

  private static final String SQL_STMT = "SELECT * FROM table";

  private final SQLCommenter commenter = new SQLCommenter();

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public void encodeComment() {
    commenter.augmentSQLStatement(SQL_STMT, TAGS);
  }

  @Benchmark
  @BenchmarkMode(Mode.AverageTime)
  @OutputTimeUnit(TimeUnit.NANOSECONDS)
  public void encodeTraceParent() {
    SQLCommenter.encodeTraceParent(Long.parseLong(traceId.toString()), spanId, samplingPriority);
  }
}
