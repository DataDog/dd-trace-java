package datadog.trace.core.scopemanager;

import com.google.common.util.concurrent.RateLimiter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.interceptor.TraceStatsCollector;
import datadog.trace.profiling.Profiler;
import datadog.trace.profiling.Session;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;

public abstract class TraceProfilingScopeManager extends ScopeInterceptor.DelegatingInterceptor {
  private static final long MAX_NANOSECONDS_BETWEEN_ACTIVATIONS = TimeUnit.SECONDS.toNanos(1);
  private static final double ACTIVATIONS_PER_SECOND = 5;
  private static final ThreadLocal<Boolean> IS_THREAD_PROFILING =
      new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
          return false;
        }
      };

  protected final RateLimiter rateLimiter = RateLimiter.create(ACTIVATIONS_PER_SECOND);

  private TraceProfilingScopeManager(final ScopeInterceptor delegate) {
    super(delegate);
  }

  public static TraceProfilingScopeManager create(
      final Double methodTraceSampleRate,
      final TraceStatsCollector statsCollector,
      final ScopeInterceptor delegate) {
    if (methodTraceSampleRate != null) {
      return new Percentage(methodTraceSampleRate, delegate);
    }
    return new Heuristical(statsCollector, delegate);
  }

  private static class Percentage extends TraceProfilingScopeManager {
    private static final BigDecimal TRACE_ID_MAX_AS_BIG_DECIMAL =
        new BigDecimal(CoreTracer.TRACE_ID_MAX);

    private final BigInteger cutoff;

    private Percentage(final double percent, final ScopeInterceptor delegate) {
      super(delegate);
      assert 0 <= percent && percent <= 1;
      cutoff = new BigDecimal(percent).multiply(TRACE_ID_MAX_AS_BIG_DECIMAL).toBigInteger();
    }

    @Override
    public Scope handleSpan(final AgentSpan span) {
      if (IS_THREAD_PROFILING.get() // don't need to waste a permit if so.
          || !shouldSample(span.getLocalRootSpan())) {
        // Do we want to apply rate limiting?
        // We don't want to wrap the scope for profiling.
        return delegate.handleSpan(span);
      }
      return new TraceProfilingScope(delegate.handleSpan(span));
    }

    private boolean shouldSample(final AgentSpan span) {
      return span.getTraceId().compareTo(cutoff) <= 0;
    }
  }

  private static class Heuristical extends TraceProfilingScopeManager {
    private volatile long lastProfileTimestamp = System.nanoTime();

    private final TraceStatsCollector statsCollector;

    private Heuristical(final TraceStatsCollector statsCollector, final ScopeInterceptor delegate) {
      super(delegate);
      this.statsCollector = statsCollector;
    }

    @Override
    public Scope handleSpan(final AgentSpan span) {
      if (IS_THREAD_PROFILING.get() // don't need to waste a permit if so.
          || !maybeInteresting(span.getLocalRootSpan())
          || !acquireProfilePermit()) {
        // We don't want to wrap the scope for profiling.
        return delegate.handleSpan(span);
      }
      lastProfileTimestamp = System.nanoTime();
      return new TraceProfilingScope(delegate.handleSpan(span));
    }

    private boolean maybeInteresting(final AgentSpan span) {
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
  }

  private static class TraceProfilingScope extends DelegatingScope {

    private final Session session;

    private TraceProfilingScope(final Scope delegate) {
      super(delegate);
      IS_THREAD_PROFILING.set(true);
      session = Profiler.startProfiling(span().getTraceId().toString());
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
    public TraceScope.Continuation capture() {
      return delegate.capture();
    }

    @Override
    public void close() {
      IS_THREAD_PROFILING.set(false);
      delegate.close();
      session.close();
    }

    @Override
    public boolean isAsyncPropagating() {
      return delegate.isAsyncPropagating();
    }
  }
}
