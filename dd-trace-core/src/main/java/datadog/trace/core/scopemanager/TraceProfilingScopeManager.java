package datadog.trace.core.scopemanager;

import com.google.common.util.concurrent.RateLimiter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import datadog.trace.core.DDSpan;
import datadog.trace.core.interceptor.TraceStatsCollector;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;

public class TraceProfilingScopeManager implements DDScopeManager {
  private static final long MAX_NANOSECONDS_BETWEEN_ACTIVATIONS = TimeUnit.SECONDS.toNanos(1);
  private static final double ACTIVATIONS_PER_SECOND = 5;
  private static final ThreadLocal<Boolean> IS_THREAD_PROFILING =
      new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
          return false;
        }
      };

  private final DDScopeManager delegate;
  private final TraceStatsCollector statsCollector;
  private final RateLimiter rateLimiter = RateLimiter.create(ACTIVATIONS_PER_SECOND);
  private volatile long lastProfileTimestamp = System.nanoTime();

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
    if (scope instanceof TraceProfilingScope
        || IS_THREAD_PROFILING.get() // don't need to waste a permit if so.
        || !(span instanceof DDSpan)
        || !maybeInteresting((DDSpan) span)
        || !acquireProfilePermit()) {
      // We don't want to wrap the scope for profiling.
      return scope;
    }
    lastProfileTimestamp = System.nanoTime();
    return new TraceProfilingScope(scope);
  }

  private boolean maybeInteresting(final DDSpan span) {
    final Histogram traceStats = statsCollector.getTraceStats(span);
    if (traceStats == null) {
      // No historical data for this trace yet.
      return false;
    }
    final Histogram overallStats = statsCollector.getOverallStats();

    final long traceAverage = traceStats.getValueAtPercentile(50);
    final long overall80 = overallStats.getValueAtPercentile(80);
    if (overall80 < traceAverage) {
      // This trace is likely to be slower than most, so lets profile it.
      return true;
    }

    final long traceCount = traceStats.getTotalCount();
    final long overallCount = overallStats.getTotalCount();
    if (3 < traceCount && traceCount < (overallCount * .9)) {
      // This is an uncommon trace (but not unique), so lets profile it.
      return true;
    }

    if (lastProfileTimestamp + MAX_NANOSECONDS_BETWEEN_ACTIVATIONS < System.nanoTime()) {
      // It's been a while since we last profiled, so lets take one now.
      // Due to the multi-threaded nature here, we will likely have multiple threads enter here
      // but they will still be subject to the rate limiter following.
      return true;
    }
    return false;
  }

  private boolean acquireProfilePermit() {
    return rateLimiter.tryAcquire();
  }

  private static class TraceProfilingScope implements AgentScope {

    private final AgentScope delegate;

    private TraceProfilingScope(final AgentScope delegate) {
      IS_THREAD_PROFILING.set(true);
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
      IS_THREAD_PROFILING.set(false);
      // Stop profiling
      delegate.close();
    }
  }
}
