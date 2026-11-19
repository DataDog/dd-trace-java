package datadog.trace.bootstrap.instrumentation.jdbc;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures the stable Oracle service-hash path.
 *
 * <pre>
 * ./gradlew :dd-java-agent:agent-bootstrap:jmh \
 *   -Pjmh.includes=JDBCConnectionContextBenchmark -Pjmh.profilers=gc
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(5)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Threads(8)
public class JDBCConnectionContextBenchmark {

  @State(Scope.Thread)
  public static class ConnectionState {
    private static final String BASE_HASH = "-6937226773133363462";

    final JDBCConnectionContext context =
        new JDBCConnectionContext(new DBInfo.Builder().type("oracle").build());

    @Setup
    public void setup() {
      context.markOracleServiceHashSet(BASE_HASH);
    }
  }

  @Benchmark
  public boolean stableHash(ConnectionState state) {
    return state.context.shouldSetOracleServiceHash(ConnectionState.BASE_HASH);
  }
}
