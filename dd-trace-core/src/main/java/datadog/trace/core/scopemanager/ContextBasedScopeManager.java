package datadog.trace.core.scopemanager;

import static datadog.trace.api.ConfigDefaults.DEFAULT_ASYNC_PROPAGATING;
import static datadog.trace.bootstrap.instrumentation.api.ScopeSource.INSTRUMENTATION;
import static datadog.trace.bootstrap.instrumentation.api.ScopeSource.ITERATION;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.context.ContextScope;
import datadog.trace.api.scopemanager.ExtendedScopeListener;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.bootstrap.instrumentation.api.ScopeState;
import datadog.trace.core.monitor.HealthMetrics;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextBasedScopeManager implements AgentScopeManager {
  private static final ContextKey<AgentSpan> SPAN_CONTEXT_KEY = ContextKey.named("span");
  private static final ContextKey<AgentScope> SPAN_SCOPE_CONTEXT_KEY =
      ContextKey.named("span-scope");
  static final Logger LOGGER = LoggerFactory.getLogger(ContextBasedScopeManager.class);

  private final boolean inheritAsyncPropagation;
  final List<ScopeListener> scopeListeners;
  final List<ExtendedScopeListener> extendedScopeListeners;
  final HealthMetrics healthMetrics;

  /**
   * Constructor.
   *
   * @param inheritAsyncPropagation Whether the next span should inherit the active span
   *     asyncPropagation flag.
   * @param healthMetrics The health metric service.
   */
  public ContextBasedScopeManager(boolean inheritAsyncPropagation, HealthMetrics healthMetrics) {
    this.inheritAsyncPropagation = inheritAsyncPropagation;
    this.scopeListeners = new CopyOnWriteArrayList<>();
    this.extendedScopeListeners = new CopyOnWriteArrayList<>();
    this.healthMetrics = healthMetrics;
  }

  private boolean defaultAsyncPropagation() {
    if (this.inheritAsyncPropagation) {
      AgentScope agentScope = active();
      return agentScope == null ? DEFAULT_ASYNC_PROPAGATING : agentScope.isAsyncPropagating();
    } else {
      return DEFAULT_ASYNC_PROPAGATING;
    }
  }

  @Override
  public AgentScope activate(AgentSpan span, ScopeSource source) {
    return activate(span, source, defaultAsyncPropagation());
  }

  @Override
  public AgentScope activate(AgentSpan span, ScopeSource source, boolean isAsyncPropagating) {
    // Check parameters
    if (span == null || source == null) {
      return AgentTracer.NoopAgentScope.INSTANCE;
    }
    // Create agent scope and attach it with span into context
    ContextBasedScope agentScope = new ContextBasedScope(this, span, source, isAsyncPropagating);
    Context context =
        Context.current().with(SPAN_CONTEXT_KEY, span).with(SPAN_SCOPE_CONTEXT_KEY, agentScope);
    ContextScope contextScope = context.makeCurrent();
    agentScope.attachContextScope(contextScope);
    // Count activation
    this.healthMetrics.onActivateScope();
    return agentScope;
  }

  @Override
  public AgentScope active() {
    return Context.current().get(SPAN_SCOPE_CONTEXT_KEY);
  }

  @Override
  public AgentSpan activeSpan() {
    return Context.current().get(SPAN_CONTEXT_KEY);
  }

  @Override
  public AgentScope.Continuation captureSpan(AgentSpan span) {
    // Return noop continuation on invalid span
    if (span == null) {
      return AgentTracer.NoopAgentScope.INSTANCE.capture();
    }
    // Create and register continuation
    ContextBasedContinuation continuation =
        new ContextBasedContinuation(this, span, INSTRUMENTATION);
    // Count continuation capture
    this.healthMetrics.onCaptureContinuation();
    return continuation;
  }

  @Override
  public void closePrevious(boolean finishSpan) {
    AgentScope agentScope = active();
    if (agentScope != null && agentScope.source() == ITERATION.id()) {
      // TODO
      // if (iterationKeepAlive > 0) { // skip depth check because cancelling is cheap
      //        cancelRootIterationScopeCleanup(scopeStack, top);
      //      }
      // TODO
      if (finishSpan) {
        // Finish span
        agentScope.span().finishWithEndToEnd();
        // Count continuation finish
        this.healthMetrics.onFinishContinuation();
      }
    }
  }

  @Override
  public AgentScope activateNext(AgentSpan span) {
    //    if (span == null) {
    //      return AgentTracer.NoopAgentScope.INSTANCE;
    //    }
    AgentScope agentScope = activate(span, ITERATION);
    // TODO
    // if (iterationKeepAlive > 0 && currentDepth == 0) {
    //      // no surrounding scope to aid cleanup, so use background task instead
    //      scheduleRootIterationScopeCleanup(scopeStack, scope);
    //    }
    // TODO
    return agentScope;
  }

  @Override
  public ScopeState newScopeState() {
    return new ContextBasedScopeState();
  }

  /**
   * Attaches a listener to scope activation events.
   *
   * @param listener The listener to attach.
   */
  public void addScopeListener(ScopeListener listener) {
    if (listener == null) {
      return;
    }
    if (listener instanceof ExtendedScopeListener) {
      addExtendedScopeListener((ExtendedScopeListener) listener);
    } else {
      this.scopeListeners.add(listener);
      LOGGER.debug("Added scope listener {}", listener);
      if (activeSpan() != null) {
        // Notify the listener about the currently active scope
        listener.afterScopeActivated();
      }
    }
  }

  private void addExtendedScopeListener(ExtendedScopeListener listener) {
    this.extendedScopeListeners.add(listener);
    LOGGER.debug("Added extended scope listener {}", listener);
    AgentSpan activeSpan = activeSpan();
    if (activeSpan != null) {
      // Notify the listener about the currently active scope
      listener.afterScopeActivated(activeSpan.getTraceId(), activeSpan.getSpanId());
    }
  }
}
