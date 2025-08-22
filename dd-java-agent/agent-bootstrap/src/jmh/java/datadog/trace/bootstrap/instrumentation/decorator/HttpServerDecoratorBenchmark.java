package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.context.Context.root;
import static datadog.trace.bootstrap.instrumentation.api.AgentSpan.fromContext;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.MICROSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.context.Context;
import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.common.writer.Writer;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import java.net.URI;
import java.util.List;
import java.util.Map;
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
import org.openjdk.jmh.annotations.Warmup;

@State(Scope.Benchmark)
@Warmup(iterations = 4, time = 30, timeUnit = SECONDS)
@Measurement(iterations = 4, time = 30, timeUnit = SECONDS)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(MICROSECONDS)
@Fork(value = 1)
public class HttpServerDecoratorBenchmark {

  @Param({"https://foo.bar:4711/normal/path", "https://foo.bar:4711/numb3r/path"})
  String url;

  Request request;
  BenchmarkHttpServerDecorator decorator;
  AgentSpan span;

  @Setup(Level.Trial)
  public void setUp() {
    request = new Request("GET", URI.create(url));
    CoreTracer tracer =
        CoreTracer.builder()
            .strictTraceWrites(
                true) // Avoid any extra bookkeeping for traces since we write directly
            .writer(new NoOpWriter()) // Avoid writing
            .build();
    GlobalTracer.forceRegister(tracer);
    decorator = new BenchmarkHttpServerDecorator();
    Context context = decorator.startSpan(emptyMap(), root());
    span = fromContext(context);
  }

  @Benchmark
  public AgentSpan onRequest() {
    return decorator.onRequest(span, null, request, root());
  }

  public static class Request {
    private final String method;
    private final URI uri;
    private final URIDataAdapter uriDataAdapter;

    public Request(String method, URI uri) {
      this.method = method;
      this.uri = uri;
      this.uriDataAdapter = new URIDefaultDataAdapter(uri);
    }

    public String method() {
      return method;
    }

    public URIDataAdapter uriDataAdapter() {
      return uriDataAdapter;
    }
  }

  public static class BenchmarkHttpServerDecorator
      extends HttpServerDecorator<Request, Void, Void, Map<String, String>> {

    private static final CharSequence COMPONENT = UTF8BytesString.create("benchmark");

    private final CharSequence SPAN_NAME;

    public BenchmarkHttpServerDecorator() {
      this.SPAN_NAME = UTF8BytesString.create(this.operationName());
    }

    @Override
    protected String[] instrumentationNames() {
      return new String[] {"benchmark"};
    }

    @Override
    protected CharSequence component() {
      return COMPONENT;
    }

    @Override
    protected AgentPropagation.ContextVisitor<Map<String, String>> getter() {
      return ContextVisitors.stringValuesMap();
    }

    @Override
    protected AgentPropagation.ContextVisitor<Void> responseGetter() {
      return null;
    }

    @Override
    public CharSequence spanName() {
      return SPAN_NAME;
    }

    @Override
    protected String method(Request request) {
      return request.method();
    }

    @Override
    protected URIDataAdapter url(Request request) {
      return request.uriDataAdapter();
    }

    @Override
    protected String peerHostIP(Void connection) {
      return null;
    }

    @Override
    protected int peerPort(Void connection) {
      return 0;
    }

    @Override
    protected int status(Void response) {
      return 0;
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
