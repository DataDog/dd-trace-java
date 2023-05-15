package datadog.trace.core.scopemanager;

import static datadog.trace.api.ConfigDefaults.DEFAULT_ASYNC_PROPAGATING;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopAgentSpan;
import static datadog.trace.bootstrap.instrumentation.api.ScopeSource.INSTRUMENTATION;
import static datadog.trace.bootstrap.instrumentation.api.ScopeSource.ITERATION;
import static datadog.trace.core.scopemanager.ScopeContext.SPAN_KEY;
import static datadog.trace.core.scopemanager.ScopeContext.fromSpan;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.Config;
import datadog.trace.api.scopemanager.ExtendedScopeListener;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.bootstrap.instrumentation.api.ScopeState;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.relocate.api.RatelimitedLogger;
import datadog.trace.util.AgentTaskScheduler;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The primary ScopeManager. This class has ownership of the core ThreadLocal containing the
 * currently active Scope. Such scopes can be suspended with a Continuation to prevent the trace
 * from being reported even if all related spans are finished. It also delegates to other
 * ScopeInterceptors to provide additional functionality.
 */
public final class ContinuableScopeManager implements AgentScopeManager {
  static final Logger log = LoggerFactory.getLogger(ContinuableScopeManager.class);
  static final RatelimitedLogger ratelimitedLog = new RatelimitedLogger(log, 1, MINUTES);
  static final long iterationKeepAlive =
      SECONDS.toMillis(Config.get().getScopeIterationKeepAlive());
  volatile ConcurrentMap<ScopeStack, ContinuableScope> rootIterationScopes;
  final List<ScopeListener> scopeListeners;
  final List<ExtendedScopeListener> extendedScopeListeners;
  final boolean strictMode;
  private final ScopeStackThreadLocal tlsScopeStack;
  private final int depthLimit;
  private final boolean inheritAsyncPropagation;
  final HealthMetrics healthMetrics;

  /**
   * Constructor with NOOP Profiling and HealthMetrics implementations.
   *
   * @param depthLimit The maximum scope depth limit, <code>0</code> for unlimited.
   * @param strictMode Whether check if the closed spans are the active ones or not.
   * @param inheritAsyncPropagation Whether the next span should inherit the active span
   *     asyncPropagation flag.
   */
  public ContinuableScopeManager(
      final int depthLimit, final boolean strictMode, final boolean inheritAsyncPropagation) {
    this(
        depthLimit,
        strictMode,
        inheritAsyncPropagation,
        ProfilingContextIntegration.NoOp.INSTANCE,
        HealthMetrics.NO_OP);
  }

  /**
   * Default constructor.
   *
   * @param depthLimit The maximum scope depth limit, <code>0</code> for unlimited.
   * @param strictMode Whether check if the closed spans are the active ones or not.
   * @param inheritAsyncPropagation Whether the next span should inherit the active span
   *     asyncPropagation flag.
   */
  public ContinuableScopeManager(
      final int depthLimit,
      final boolean strictMode,
      final boolean inheritAsyncPropagation,
      final ProfilingContextIntegration profilingContextIntegration,
      final HealthMetrics healthMetrics) {
    this.depthLimit = depthLimit == 0 ? Integer.MAX_VALUE : depthLimit;
    this.strictMode = strictMode;
    this.inheritAsyncPropagation = inheritAsyncPropagation;
    this.scopeListeners = new CopyOnWriteArrayList<>();
    this.extendedScopeListeners = new CopyOnWriteArrayList<>();
    this.healthMetrics = healthMetrics;
    this.tlsScopeStack = new ScopeStackThreadLocal(profilingContextIntegration);
  }

  @Override
  public AgentScope activate(final AgentSpan span, final ScopeSource source) {
    return activate(fromSpan(span), source.id(), false, /* ignored */ false);
  }

  @Override
  public AgentScope activate(
      final AgentSpan span, final ScopeSource source, boolean isAsyncPropagating) {
    return activate(fromSpan(span), source.id(), true, isAsyncPropagating);
  }

  @Override
  public AgentScope.Continuation captureSpan(final AgentSpan span) {
    SingleContinuation continuation =
        new SingleContinuation(this, fromSpan(span), INSTRUMENTATION.id());
    continuation.register();
    healthMetrics.onCaptureContinuation();
    return continuation;
  }

  private AgentScope activate(
      final AgentScopeContext context,
      final byte source,
      final boolean overrideAsyncPropagation,
      final boolean isAsyncPropagating) {
    ScopeStack scopeStack = scopeStack();

    final ContinuableScope top = scopeStack.top;
    if (top != null && top.context().equals(context)) {
      top.incrementReferences();
      return top;
    }

    // DQH - This check could go before the check above, since depth limit checking is fast
    final int currentDepth = scopeStack.depth();
    if (depthLimit <= currentDepth) {
      healthMetrics.onScopeStackOverflow();
      log.debug("Scope depth limit exceeded ({}).  Returning NoopScope.", currentDepth);
      return AgentTracer.NoopAgentScope.INSTANCE;
    }

    assert context != null;

    // Inherit the async propagation from the active scope unless the value is overridden
    boolean asyncPropagation =
        overrideAsyncPropagation
            ? isAsyncPropagating
            : inheritAsyncPropagation && top != null
                ? top.isAsyncPropagating()
                : DEFAULT_ASYNC_PROPAGATING;

    final ContinuableScope scope = new ContinuableScope(this, context, source, asyncPropagation);
    scopeStack.push(scope);
    healthMetrics.onActivateScope();

    return scope;
  }

  /**
   * Creates a new scope when a {@link AbstractContinuation} is activated.
   *
   * @param continuation {@code null} if a continuation is re-used
   */
  ContinuableScope continueSpan(
      final AbstractContinuation continuation, final AgentScopeContext context, final byte source) {

    final ContinuableScope scope;
    if (continuation != null) {
      scope = new ContinuingScope(this, context, source, true, continuation);
    } else {
      scope = new ContinuableScope(this, context, source, true);
    }

    scopeStack().push(scope);

    return scope;
  }

  @Override
  public void closePrevious(final boolean finishSpan) {
    ScopeStack scopeStack = scopeStack();

    // close any immediately previous iteration scope
    final ContinuableScope top = scopeStack.top;
    if (top != null && top.source() == ITERATION.id()) {
      if (iterationKeepAlive > 0) { // skip depth check because cancelling is cheap
        cancelRootIterationScopeCleanup(scopeStack, top);
      }
      top.close();
      scopeStack.cleanup();
      if (finishSpan) {
        if (top.span() != null) {
          top.span().finishWithEndToEnd();
        }
        healthMetrics.onFinishContinuation();
      }
    }
  }

  @Override
  public AgentScope activateNext(final AgentSpan span) {
    ScopeStack scopeStack = scopeStack();

    final int currentDepth = scopeStack.depth();
    if (depthLimit <= currentDepth) {
      healthMetrics.onScopeStackOverflow();
      log.debug("Scope depth limit exceeded ({}).  Returning NoopScope.", currentDepth);
      return AgentTracer.NoopAgentScope.INSTANCE;
    }

    assert span != null;

    final ContinuableScope top = scopeStack.top;

    AgentScopeContext context =
        (top == null ? ScopeContext.empty() : top.context).with(SPAN_KEY, span);

    boolean asyncPropagation =
        inheritAsyncPropagation && top != null
            ? top.isAsyncPropagating()
            : DEFAULT_ASYNC_PROPAGATING;

    final ContinuableScope scope =
        new ContinuableScope(this, context, ITERATION.id(), asyncPropagation);

    if (iterationKeepAlive > 0 && currentDepth == 0) {
      // no surrounding scope to aid cleanup, so use background task instead
      scheduleRootIterationScopeCleanup(scopeStack, scope);
    }

    scopeStack.push(scope);

    return scope;
  }

  @Override
  public AgentScope active() {
    return scopeStack().active();
  }

  @Override
  public AgentSpan activeSpan() {
    final ContinuableScope active = scopeStack().active();
    return active == null ? null : active.span();
  }

  @Override
  public AgentScope activateContext(AgentScopeContext context) {

    return this.activate(context, INSTRUMENTATION.id(), false, false);
  }

  /** Attach a listener to scope activation events */
  public void addScopeListener(final ScopeListener listener) {
    if (listener instanceof ExtendedScopeListener) {
      addExtendedScopeListener((ExtendedScopeListener) listener);
    } else {
      scopeListeners.add(listener);
      log.debug("Added scope listener {}", listener);
      AgentSpan activeSpan = activeSpan();
      if (activeSpan != null) {
        // Notify the listener about the currently active scope
        listener.afterScopeActivated();
      }
    }
  }

  private void addExtendedScopeListener(final ExtendedScopeListener listener) {
    extendedScopeListeners.add(listener);
    log.debug("Added scope listener {}", listener);
    AgentSpan activeSpan = activeSpan();
    if (activeSpan != null && activeSpan != NoopAgentSpan.INSTANCE) {
      // Notify the listener about the currently active scope
      listener.afterScopeActivated(
          activeSpan.getTraceId(),
          activeSpan.getLocalRootSpan().getSpanId(),
          activeSpan.context().getSpanId());
    }
  }

  ScopeStack scopeStack() {
    return this.tlsScopeStack.get();
  }

  @Override
  public ScopeState newScopeState() {
    return new ContinuableScopeState();
  }

  private class ContinuableScopeState implements ScopeState {

    private ScopeStack localScopeStack = tlsScopeStack.initialValue();

    @Override
    public void activate() {
      tlsScopeStack.set(localScopeStack);
    }

    @Override
    public void fetchFromActive() {
      localScopeStack = tlsScopeStack.get();
    }
  }

  static final class ScopeStackThreadLocal extends ThreadLocal<ScopeStack> {

    private final ProfilingContextIntegration profilingContextIntegration;

    ScopeStackThreadLocal(ProfilingContextIntegration profilingContextIntegration) {
      this.profilingContextIntegration = profilingContextIntegration;
    }

    @Override
    protected ScopeStack initialValue() {
      return new ScopeStack(profilingContextIntegration);
    }
  }

  private void scheduleRootIterationScopeCleanup(ScopeStack scopeStack, ContinuableScope scope) {
    if (rootIterationScopes == null) {
      synchronized (this) {
        if (rootIterationScopes == null) {
          rootIterationScopes = new ConcurrentHashMap<>();
          RootIterationCleaner.scheduleFor(rootIterationScopes);
        }
      }
    }
    rootIterationScopes.put(scopeStack, scope);
  }

  private void cancelRootIterationScopeCleanup(ScopeStack scopeStack, ContinuableScope scope) {
    if (rootIterationScopes != null) {
      rootIterationScopes.remove(scopeStack, scope);
    }
  }

  /**
   * Background task to clean-up scopes from overdue root iterations that have no surrounding scope.
   */
  private static final class RootIterationCleaner
      implements AgentTaskScheduler.Task<Map<ScopeStack, ContinuableScope>> {
    private static final RootIterationCleaner CLEANER = new RootIterationCleaner();

    public static void scheduleFor(Map<ScopeStack, ContinuableScope> rootIterationScopes) {
      long period = Math.min(iterationKeepAlive, 10_000);
      AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
          CLEANER, rootIterationScopes, iterationKeepAlive, period, TimeUnit.MILLISECONDS);
    }

    @Override
    public void run(Map<ScopeStack, ContinuableScope> rootIterationScopes) {
      Iterator<Map.Entry<ScopeStack, ContinuableScope>> itr =
          rootIterationScopes.entrySet().iterator();

      long cutOff = System.currentTimeMillis() - iterationKeepAlive;

      while (itr.hasNext()) {
        Map.Entry<ScopeStack, ContinuableScope> entry = itr.next();

        ScopeStack scopeStack = entry.getKey();
        ContinuableScope rootScope = entry.getValue();

        if (!rootScope.alive() || rootScope.span() == null) { // no need to track this anymore
          itr.remove();
        } else if (NANOSECONDS.toMillis(rootScope.span().getStartTime()) < cutOff) {
          // mark scope as overdue to allow cleanup and avoid further spans being attached
          scopeStack.overdueRootScope = rootScope;
          rootScope.span().finishWithEndToEnd();
          itr.remove();
        }
      }
    }
  }
}
