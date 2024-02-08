package com.datadog.iast.propagation;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

import com.datadog.iast.IastSystem;
import com.datadog.iast.model.Range;
import com.datadog.iast.model.Source;
import com.datadog.iast.taint.TaintedObjects;
import datadog.trace.api.Config;
import datadog.trace.api.ProductActivation;
import datadog.trace.api.gateway.InstrumentationGateway;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.iast.IastContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.TagContext;
import datadog.trace.common.writer.Writer;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import java.util.List;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@State(Scope.Thread)
@OutputTimeUnit(NANOSECONDS)
@BenchmarkMode(Mode.SingleShotTime)
@Warmup(iterations = 50_000)
@Measurement(iterations = 5_000)
@Fork(value = 3)
public abstract class AbstractBenchmark<C extends AbstractBenchmark.BenchmarkContext> {

  private static final Logger LOG = LoggerFactory.getLogger(AbstractBenchmark.class);

  private AgentSpan span;
  private AgentScope scope;
  protected C context;

  @Setup(Level.Trial)
  public void setup() {
    final InstrumentationGateway gateway = new InstrumentationGateway();
    IastSystem.start(gateway.getSubscriptionService(RequestContextSlot.IAST));
    final CoreTracer tracer =
        CoreTracer.builder().instrumentationGateway(gateway).writer(new NoOpWriter()).build();
    AgentTracer.forceRegister(tracer);
  }

  @Setup(Level.Iteration)
  public void start() {
    context = initializeContext();
    final TagContext tagContext = new TagContext();
    if (Config.get().getIastActivation() == ProductActivation.FULLY_ENABLED) {
      tagContext.withRequestContextDataIast(context.getIastContext());
    }
    span = AgentTracer.startSpan("benchmark", tagContext);
    scope = AgentTracer.activateSpan(span);
  }

  @TearDown(Level.Iteration)
  public void stop() {
    scope.close();
    span.finish();
  }

  protected abstract C initializeContext();

  protected <E> E tainted(final IastContext context, final E value, final Range... ranges) {
    final E result = notTainted(value);
    final TaintedObjects taintedObjects = context.getTaintedObjects();
    taintedObjects.taint(result, ranges);
    return result;
  }

  @SuppressWarnings({"StringOperationCanBeSimplified", "unchecked"})
  protected <E> E notTainted(final E value) {
    final E result;
    if (value instanceof String) {
      result = (E) new String((String) value);
    } else {
      result = value;
    }
    computeHash(result); // compute it before to ensure all tests compare the same
    return result;
  }

  protected Source source() {
    return new Source((byte) 0, "key", "value");
  }

  private static long computeHash(final Object value) {
    final long hash = System.identityHashCode(value);
    LOG.trace("{} hash: {}", value, hash);
    return hash;
  }

  protected abstract static class BenchmarkContext {

    private final IastContext iastContext;

    protected BenchmarkContext(final IastContext iasContext) {
      this.iastContext = iasContext;
    }

    public IastContext getIastContext() {
      return iastContext;
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
