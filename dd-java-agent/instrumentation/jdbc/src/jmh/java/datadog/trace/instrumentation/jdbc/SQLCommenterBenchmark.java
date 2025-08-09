package datadog.trace.instrumentation.jdbc;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
public class SQLCommenterBenchmark {

  private static final String traceParent =
      "00-00000000000000007fffffffffffffff-000000024cb016ea-01";
  private static final String dbService = "users-db";
  private static final String hostname = "my-host";
  private static final String dbName = "credit-card-numbers";
  private static final String parentService = "parent";
  private static final String env = "env";
  private static final String version = "version";
  private static final String serviceHash = "service-hash";
  private static final boolean injectTrace = true;

  @Benchmark
  public void testToComment() {
    StringBuilder stringBuilder = new StringBuilder();
    SQLCommenter.toComment(
        stringBuilder,
        injectTrace,
        parentService,
        dbService,
        hostname,
        dbName,
        null,
        env,
        version,
        traceParent,
        serviceHash);
  }
}
