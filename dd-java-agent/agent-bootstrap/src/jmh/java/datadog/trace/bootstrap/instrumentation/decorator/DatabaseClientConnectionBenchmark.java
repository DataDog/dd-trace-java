package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_USER;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.TagExtractor;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.writer.Writer;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
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
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Acceptance test for the {@code dbUser} peel: measures the param-injected connection-tags {@link
 * TagExtractor} form of {@link DatabaseClientDecorator#onConnection(AgentSpan, Object,
 * TagExtractor)} — the shape that replaced the sparsely-overridden {@code dbUser} template method.
 *
 * <p>One decorator type is used throughout (so the receiver and the {@code dbInstance}/{@code
 * dbHostname} template calls stay monomorphic); only the injected extractor varies, to isolate the
 * question raised in review: does passing the extractor as a caller-side constant devirtualize and
 * inline?
 *
 * <p>{@code mode}:
 *
 * <ul>
 *   <li><b>mono</b> — a single {@code static final} extractor at the call site (the production
 *       shape: each integration's advice passes its own constant). Expect {@code extract()} to
 *       devirtualize and inline when {@code onConnection} inlines.
 *   <li><b>mega</b> — {@link #TYPES} distinct extractor types cycled through the one site (the
 *       worst case: a single shared site that never sees a stable type). Characterizes the downside
 *       if the call is <em>not</em> inlined.
 * </ul>
 *
 * <p>Run with {@code -XX:+UnlockDiagnosticVMOptions -XX:+PrintInlining} to confirm the mechanism
 * (the tree, not just the number) — look for {@code DatabaseClientDecorator::onConnection} and the
 * extractor {@code extract} bodies.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(3)
@Threads(8)
public class DatabaseClientConnectionBenchmark {

  private static final int TYPES = 8;
  private static final String CONN = "bench-user";

  /** The mono call site: a single static-final constant extractor. */
  private static final TagExtractor<String> MONO = new E0();

  @Param({"mono", "mega"})
  String mode;

  private boolean mega;
  private BenchDbDecorator decorator;
  private AgentSpan span;
  private TagExtractor<String>[] extractors;
  private int idx;

  @SuppressWarnings("unchecked")
  @Setup(Level.Trial)
  public void setUp() {
    CoreTracer tracer =
        CoreTracer.builder().strictTraceWrites(true).writer(new NoOpWriter()).build();
    GlobalTracer.forceRegister(tracer);
    decorator = new BenchDbDecorator();
    span = tracer.startSpan("benchmark", "db.query");
    extractors =
        new TagExtractor[] {
          new E0(), new E1(), new E2(), new E3(), new E4(), new E5(), new E6(), new E7()
        };
    mega = "mega".equals(mode);
  }

  @Benchmark
  public AgentSpan onConnection() {
    if (mega) {
      int i = idx;
      idx = (i + 1 == TYPES) ? 0 : i + 1;
      return decorator.onConnection(span, CONN, extractors[i]);
    }
    return decorator.onConnection(span, CONN, MONO);
  }

  // Eight structurally-identical but distinct extractor types (so the mega site sees a rotating
  // type).
  static final class E0 implements TagExtractor<String> {
    public void extract(String c, AgentSpan s) {
      s.setTag(DB_USER, c);
    }
  }

  static final class E1 implements TagExtractor<String> {
    public void extract(String c, AgentSpan s) {
      s.setTag(DB_USER, c);
    }
  }

  static final class E2 implements TagExtractor<String> {
    public void extract(String c, AgentSpan s) {
      s.setTag(DB_USER, c);
    }
  }

  static final class E3 implements TagExtractor<String> {
    public void extract(String c, AgentSpan s) {
      s.setTag(DB_USER, c);
    }
  }

  static final class E4 implements TagExtractor<String> {
    public void extract(String c, AgentSpan s) {
      s.setTag(DB_USER, c);
    }
  }

  static final class E5 implements TagExtractor<String> {
    public void extract(String c, AgentSpan s) {
      s.setTag(DB_USER, c);
    }
  }

  static final class E6 implements TagExtractor<String> {
    public void extract(String c, AgentSpan s) {
      s.setTag(DB_USER, c);
    }
  }

  static final class E7 implements TagExtractor<String> {
    public void extract(String c, AgentSpan s) {
      s.setTag(DB_USER, c);
    }
  }

  static final class BenchDbDecorator extends DatabaseClientDecorator<String> {
    private static final CharSequence COMPONENT = UTF8BytesString.create("benchmark");

    @Override
    protected String[] instrumentationNames() {
      return new String[] {"benchmark"};
    }

    @Override
    protected String service() {
      return "benchmark-db";
    }

    @Override
    protected CharSequence component() {
      return COMPONENT;
    }

    @Override
    protected CharSequence spanType() {
      return "sql";
    }

    @Override
    protected String dbType() {
      return "benchdb";
    }

    @Override
    protected String dbInstance(String connection) {
      return connection;
    }

    @Override
    protected CharSequence dbHostname(String connection) {
      return null;
    }
  }

  private static class NoOpWriter implements Writer {
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
