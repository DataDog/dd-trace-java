package datadog.trace.bootstrap.instrumentation.decorator;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.Writer;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures {@link BaseDecorator#onPeerConnection} on a real {@link DDSpan}. This is the
 * tag-id-keyed fast-path (peer.hostname / peer.ipv4) end-to-end through the span/context/TagMap
 * layers: compare this branch (id-keyed, slotted) against the prior commit (string-keyed, bucketed)
 * by running the same benchmark on each.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 5, timeUnit = SECONDS)
@Measurement(iterations = 5, time = 5, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(NANOSECONDS)
@Fork(value = 1)
public class PeerConnectionBenchmark {

  BenchmarkDecorator decorator;
  InetSocketAddress connection;
  AgentSpan span;

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    CoreTracer tracer =
        CoreTracer.builder().strictTraceWrites(true).writer(new NoOpWriter()).build();
    GlobalTracer.forceRegister(tracer);
    decorator = new BenchmarkDecorator();
    span = tracer.startSpan("benchmark", "peer.connection");
    // resolved IPv4 address carrying an explicit host name, so onPeerConnection exercises
    // peer.hostname + peer.ipv4 without triggering a reverse-DNS lookup.
    InetAddress address = InetAddress.getByAddress("benchmark.host", new byte[] {10, 0, 0, 1});
    connection = new InetSocketAddress(address, 8080);
  }

  @Benchmark
  public AgentSpan onPeerConnection() {
    return decorator.onPeerConnection(span, connection);
  }

  static final class BenchmarkDecorator extends BaseDecorator {
    @Override
    protected String[] instrumentationNames() {
      return new String[] {"benchmark"};
    }

    @Override
    protected CharSequence spanType() {
      return "benchmark";
    }

    @Override
    protected CharSequence component() {
      return "benchmark";
    }
  }

  private static final class NoOpWriter implements Writer {
    @Override
    public void write(final List<DDSpan> trace) {}

    @Override
    public void start() {}

    @Override
    public boolean flush() {
      return false;
    }

    @Override
    public void close() {}

    @Override
    public void incrementDropCounts(final int spanCount) {}
  }
}
