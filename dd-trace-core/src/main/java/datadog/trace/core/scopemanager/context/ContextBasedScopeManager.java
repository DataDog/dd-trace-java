package datadog.trace.core.scopemanager.context;

import static datadog.trace.api.ConfigDefaults.DEFAULT_ASYNC_PROPAGATING;
import static datadog.trace.api.tracing.ContextKeys.SPAN_CONTEXT_KEY;
import static datadog.trace.api.tracing.ContextKeys.SPAN_SCOPE_CONTEXT_KEY;
import static datadog.trace.bootstrap.instrumentation.api.ScopeSource.INSTRUMENTATION;
import static datadog.trace.bootstrap.instrumentation.api.ScopeSource.ITERATION;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.bootstrap.instrumentation.api.ScopeState;
import datadog.trace.core.monitor.HealthMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContextBasedScopeManager implements AgentScopeManager {
  static final Logger LOGGER = LoggerFactory.getLogger(ContextBasedScopeManager.class);

  private final boolean inheritAsyncPropagation;
  final ScopeEventManager eventManager;
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
    this.eventManager = new ScopeEventManager(this::activeSpan);
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
    // TODO Start-Dev notes
    // Re-activating the same span should not trigger activate and closed events
    // Improve Scope creation by reducing context array allocation
    // Need to check about the health metrics expectation for this case
    // TODO End-Dev notes
    // TODO Start-Room for improvement
    ContextBasedScope agentScope = new ContextBasedScope(this, span, source, isAsyncPropagating);
    Context current = Context.current();
    Context context = current.with(SPAN_CONTEXT_KEY, span).with(SPAN_SCOPE_CONTEXT_KEY, agentScope);
    ContextScope contextScope = context.makeCurrent();
    agentScope.attachContextScope(contextScope);
    // TODO End-Room for improvement
    // Notify new activation
    if (current.get(SPAN_CONTEXT_KEY) != span) {
      this.eventManager.onScopeActivated(span);
      this.healthMetrics.onActivateScope();
    }
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
    this.eventManager.addScopeListener(listener);
  }
}
