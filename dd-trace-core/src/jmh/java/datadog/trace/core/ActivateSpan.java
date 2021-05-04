package datadog.trace.core;

import datadog.trace.api.DDId;
import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.common.writer.Writer;
import datadog.trace.context.ScopeListener;
import java.util.Collections;
import java.util.List;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

@State(Scope.Benchmark)
public class ActivateSpan {

  CoreTracer tracer;
  PendingTrace trace;
  DDSpan span1;
  DDSpan span2;
  CountingScopeListener countingScopeListener;

  @Setup(Level.Trial)
  public void init() {
    tracer = CoreTracer.builder().writer(new VoidWriter()).strictTraceWrites(true).build();

    DDId traceId = DDId.from(1);
    trace = tracer.createTrace(traceId);
    GlobalTracer.forceRegister(tracer);
    countingScopeListener = new CountingScopeListener();
    tracer.addScopeListener(countingScopeListener);

    span1 =
        DDSpan.create(
            System.currentTimeMillis() * 1000,
            new DDSpanContext(
                traceId,
                DDId.from(2),
                DDId.ZERO,
                null,
                "service",
                "operation",
                "resource",
                1,
                null,
                Collections.<String, String>emptyMap(),
                false,
                "type",
                0,
                trace));
    span2 =
        DDSpan.create(
            System.currentTimeMillis() * 1000,
            new DDSpanContext(
                traceId,
                DDId.from(3),
                DDId.from(1),
                null,
                "service",
                "operation",
                "resource",
                1,
                null,
                Collections.<String, String>emptyMap(),
                false,
                "type",
                0,
                trace));
  }

  @TearDown(Level.Trial)
  public void end(Blackhole blackhole) {
    blackhole.consume(countingScopeListener.getCount());
  }

  @Benchmark
  public void singleScope(Blackhole blackhole) {
    AgentScope scope = tracer.activateSpan(span1);
    blackhole.consume(scope);
    scope.close();
  }

  @Benchmark
  public void singleNoopScope(Blackhole blackhole) {
    AgentScope scope = tracer.activateSpan(AgentTracer.NoopAgentSpan.INSTANCE);
    blackhole.consume(scope);
    scope.close();
  }

  @Benchmark
  public void singleActivateNoopScope(Blackhole blackhole) {
    AgentScope scope = tracer.activateNoopScope();
    blackhole.consume(scope);
    scope.close();
  }

  @Benchmark
  public void dualScope(Blackhole blackhole) {
    AgentScope scope1 = tracer.activateSpan(span1);
    AgentScope scope2 = tracer.activateSpan(span2);
    blackhole.consume(scope2);
    scope2.close();
    blackhole.consume(scope1);
    scope1.close();
  }

  @Benchmark
  public void dualNoopScope(Blackhole blackhole) {
    AgentScope scope1 = tracer.activateSpan(AgentTracer.NoopAgentSpan.INSTANCE);
    AgentScope scope2 = tracer.activateSpan(AgentTracer.NoopAgentSpan.INSTANCE);
    blackhole.consume(scope2);
    scope2.close();
    blackhole.consume(scope1);
    scope1.close();
  }

  @Benchmark
  public void dualActivateNoopScope(Blackhole blackhole) {
    AgentScope scope1 = tracer.activateNoopScope();
    AgentScope scope2 = tracer.activateNoopScope();
    blackhole.consume(scope2);
    scope2.close();
    blackhole.consume(scope1);
    scope1.close();
  }

  public static final class VoidWriter implements Writer {
    @Override
    public void write(List<DDSpan> trace) {}

    @Override
    public void start() {}

    @Override
    public boolean flush() {
      return true;
    }

    @Override
    public void close() {}

    @Override
    public void incrementDropCounts(int spanCount) {}
  }

  public static final class CountingScopeListener implements ScopeListener {
    private long count;

    @Override
    public void afterScopeActivated() {
      count++;
    }

    @Override
    public void afterScopeClosed() {
      count++;
    }

    public long getCount() {
      return count;
    }
  }
}
