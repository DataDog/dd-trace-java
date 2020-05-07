package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import datadog.trace.core.DDSpan;
import datadog.trace.core.interceptor.TraceStatsCollector;
import org.HdrHistogram.Histogram;

public class TraceProfilingScopeManager implements DDScopeManager {
  private final DDScopeManager delegate;
  private final TraceStatsCollector statsCollector;

  public TraceProfilingScopeManager(
      final DDScopeManager delegate, final TraceStatsCollector statsCollector) {
    this.delegate = delegate;
    this.statsCollector = statsCollector;
  }

  @Override
  public AgentScope activate(final AgentSpan span) {
    return considerProfiling(delegate.activate(span), span);
  }

  @Override
  public TraceScope active() {
    return delegate.active();
  }

  @Override
  public AgentSpan activeSpan() {
    return delegate.activeSpan();
  }

  private AgentScope considerProfiling(final AgentScope scope, final AgentSpan span) {
    if (!maybeInteresting(span) || scope instanceof TraceProfilingScope) {
      return scope;
    }
    return new TraceProfilingScope(scope);
  }

  private boolean maybeInteresting(final AgentSpan span) {
    if (span instanceof DDSpan) {
      final Histogram overallStats = statsCollector.getOverallStats();
      final Histogram traceStats = statsCollector.getTraceStats((DDSpan) span);

      final long overall90 = overallStats.getValueAtPercentile(90);
      final long traceAverage = traceStats.getValueAtPercentile(50);
      return overall90 < traceAverage;
      // TODO: keep track of how often this triggers and capture a minimum threshold.
    }
    return false;
  }

  private static class TraceProfilingScope implements AgentScope {

    private final AgentScope delegate;

    private TraceProfilingScope(final AgentScope delegate) {
      // Start profiling
      this.delegate = delegate;
    }

    @Override
    public AgentSpan span() {
      return delegate.span();
    }

    @Override
    public void setAsyncPropagation(final boolean value) {
      delegate.setAsyncPropagation(value);
    }

    @Override
    public void close() {
      // Stop profiling
      delegate.close();
    }
  }
}
