package datadog.opentracing;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.bootstrap.instrumentation.api.ScopeState;
import datadog.trace.util.AgentTaskScheduler;
import io.opentracing.Scope;
import io.opentracing.ScopeManager;
import io.opentracing.Span;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * Allows custom OpenTracing ScopeManagers used by CoreTracer
 *
 * <p>Normal case:
 *
 * <p>CoreTracer.scopeManager = ContextualScopeManager
 *
 * <p>DDTracer.scopeManager = OTScopeManager wrapping CoreTracer.scopeManager
 *
 * <p>Custom case:
 *
 * <p>CoreTracer.scopeManager = CustomScopeManagerWrapper wrapping passed in scopemanager
 *
 * <p>DDTracer.scopeManager = passed in scopemanager
 */
class CustomScopeManagerWrapper implements AgentScopeManager {
  private static final String DD_ITERATION = "_dd.iteration";

  private static final boolean CAN_GET_ACTIVE_SPAN;
  private static final boolean CAN_GET_ACTIVE_SCOPE;

  static {
    boolean canGetActiveScope = true;
    boolean canGetActiveSpan = true;
    try {
      ScopeManager.class.getMethod("active");
    } catch (Throwable e) {
      canGetActiveScope = false;
    }
    try {
      ScopeManager.class.getMethod("activeSpan");
    } catch (Throwable e) {
      canGetActiveSpan = false;
    }

    CAN_GET_ACTIVE_SCOPE = canGetActiveScope;
    CAN_GET_ACTIVE_SPAN = canGetActiveSpan;
  }

  private final ScopeManager delegate;
  private final TypeConverter converter;

  static final long iterationKeepAlive =
      SECONDS.toMillis(Config.get().getScopeIterationKeepAlive());

  volatile ConcurrentMap<Thread, IterationSpanStack> iterationSpans;

  CustomScopeManagerWrapper(final ScopeManager scopeManager, final TypeConverter converter) {
    delegate = scopeManager;
    this.converter = converter;
  }

  @Override
  public AgentScope activate(final AgentSpan agentSpan, final ScopeSource source) {
    final Span span = converter.toSpan(agentSpan);
    final Scope scope = delegate.activate(span);
    return converter.toAgentScope(span, scope);
  }

  @Override
  public AgentScope activate(
      final AgentSpan agentSpan, final ScopeSource source, boolean isAsyncPropagating) {
    final Span span = converter.toSpan(agentSpan);
    final Scope scope = delegate.activate(span);
    final AgentScope agentScope = converter.toAgentScope(span, scope);
    agentScope.setAsyncPropagation(isAsyncPropagating);
    return agentScope;
  }

  private Span delegateActiveSpan() {
    if (CAN_GET_ACTIVE_SPAN) {
      return delegate.activeSpan();
    } else {
      Scope scope = delegate.active();
      return scope == null ? null : scope.span();
    }
  }

  @Override
  public AgentScope active() {
    return converter.toAgentScope(
        delegateActiveSpan(), CAN_GET_ACTIVE_SCOPE ? delegate.active() : null);
  }

  @Override
  public AgentSpan activeSpan() {
    return converter.toAgentSpan(delegateActiveSpan());
  }

  @Override
  public AgentScope.Continuation captureSpan(final AgentSpan span) {
    // I can't see a better way to do this, and I don't know if this even makes sense.
    try (AgentScope scope = this.activate(span, ScopeSource.INSTRUMENTATION)) {
      return scope.capture();
    }
  }

  @Override
  public void closePrevious(final boolean finishSpan) {
    Span span = delegateActiveSpan();
    if (span != null) {
      AgentSpan agentSpan = converter.toAgentSpan(span);
      if (agentSpan != null && agentSpan.getTag(DD_ITERATION) != null) {
        if (iterationKeepAlive > 0) {
          cancelIterationSpanCleanup(agentSpan);
        }
        if (CAN_GET_ACTIVE_SCOPE) {
          delegate.active().close();
        }
        if (finishSpan) {
          agentSpan.finishWithEndToEnd();
        }
      }
    }
  }

  @Override
  public AgentScope activateNext(final AgentSpan agentSpan) {
    agentSpan.setTag(DD_ITERATION, "true");
    Span span = converter.toSpan(agentSpan);
    Scope scope = delegate.activate(span);
    if (iterationKeepAlive > 0) {
      scheduleIterationSpanCleanup(agentSpan);
    }
    return converter.toAgentScope(span, scope);
  }

  @Override
  public ScopeState newScopeState() {
    return new CustomScopeState();
  }

  private class CustomScopeState implements ScopeState {

    private AgentSpan span = activeSpan();

    @Override
    public void activate() {
      CustomScopeManagerWrapper.this.activate(span, ScopeSource.INSTRUMENTATION);
    }

    @Override
    public void fetchFromActive() {
      span = activeSpan();
    }
  }

  private void scheduleIterationSpanCleanup(final AgentSpan span) {
    if (iterationSpans == null) {
      synchronized (this) {
        if (iterationSpans == null) {
          iterationSpans = new ConcurrentHashMap<Thread, IterationSpanStack>();
          CustomScopeManagerWrapper.IterationCleaner.scheduleFor(iterationSpans);
        }
      }
    }
    IterationSpanStack spanStack = iterationSpans.get(Thread.currentThread());
    if (spanStack == null) {
      iterationSpans.put(Thread.currentThread(), spanStack = new IterationSpanStack());
    }
    spanStack.trackSpan(span);
  }

  private void cancelIterationSpanCleanup(AgentSpan span) {
    if (iterationSpans != null) {
      IterationSpanStack spanStack = iterationSpans.get(Thread.currentThread());
      if (spanStack != null) {
        spanStack.untrackSpan(span);
      }
    }
  }

  /** Background task to clean-up spans from overdue iterations. */
  private static final class IterationCleaner
      implements AgentTaskScheduler.Task<Map<Thread, IterationSpanStack>> {
    private static final CustomScopeManagerWrapper.IterationCleaner CLEANER =
        new CustomScopeManagerWrapper.IterationCleaner();

    public static void scheduleFor(Map<Thread, IterationSpanStack> iterationSpans) {
      long period = Math.min(iterationKeepAlive, 10_000);
      AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
          CLEANER, iterationSpans, iterationKeepAlive, period, TimeUnit.MILLISECONDS);
    }

    @Override
    public void run(Map<Thread, IterationSpanStack> iterationSpans) {
      Iterator<Map.Entry<Thread, IterationSpanStack>> itr = iterationSpans.entrySet().iterator();

      long cutOff = System.currentTimeMillis() - iterationKeepAlive;

      while (itr.hasNext()) {
        Map.Entry<Thread, IterationSpanStack> entry = itr.next();

        Thread thread = entry.getKey();
        IterationSpanStack spanStack = entry.getValue();

        if (thread.isAlive()) {
          spanStack.finishOverdueSpans(cutOff);
        } else { // thread has stopped
          spanStack.finishAllSpans();
          itr.remove();
        }
      }
    }
  }

  private static final class IterationSpanStack {
    private final Deque<AgentSpan> spans = new ArrayDeque<>();

    public void trackSpan(AgentSpan span) {
      synchronized (spans) {
        spans.push(span);
      }
    }

    public void untrackSpan(AgentSpan span) {
      synchronized (spans) {
        spans.remove(span);
      }
    }

    public void finishOverdueSpans(long cutOff) {
      while (true) {
        AgentSpan s;
        synchronized (spans) {
          s = spans.peek();
          if (s == null || cutOff <= NANOSECONDS.toMillis(s.getStartTime())) {
            break; // no more spans, or span started after the cut-off (keeps previous spans alive)
          }
          spans.poll();
        }
        s.finishWithEndToEnd();
      }
    }

    public void finishAllSpans() {
      synchronized (spans) {
        for (AgentSpan s : spans) {
          s.finishWithEndToEnd();
        }
        // no need to clear as this is only called when the owning thread is no longer alive
      }
    }
  }
}
