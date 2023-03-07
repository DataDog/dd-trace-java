package datadog.trace.instrumentation.jdbc;

import datadog.trace.api.DDTraceId;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class SQLCommenterBenchmark {

  private SQLCommenter sqlCommenter;
  private static final long spanId = 9876543210L;
  private static final DDTraceId traceId = DDTraceId.from(Long.MAX_VALUE);
  private static final Integer samplingPriority = 1;

  @Setup
  public void setup() {
    String injectionMode = "service";
    String sql = "SELECT * FROM users WHERE id = ?";
    String dbService = "users-db";
    sqlCommenter = new SQLCommenter(injectionMode, sql, dbService);
  }

  //  @Benchmark
  //  public void testInject() {
  //    sqlCommenter.inject();
  //  }

  @Benchmark
  public void testEncodeTraceParent() {
    SQLCommenter.encodeTraceParent(Long.parseLong(traceId.toString()), spanId, samplingPriority);
  }
}
