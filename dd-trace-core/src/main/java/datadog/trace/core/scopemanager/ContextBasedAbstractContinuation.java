package datadog.trace.core.scopemanager;

import static datadog.trace.core.scopemanager.ContextBasedScopeManager.LOGGER;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;

public abstract class ContextBasedAbstractContinuation implements AgentScope.Continuation {
  private final AgentScopeManager scopeManager;
  private final AgentSpan span;
  private final ScopeSource source;

  ContextBasedAbstractContinuation(
      AgentScopeManager scopeManager, AgentSpan span, ScopeSource source) {
    this.scopeManager = scopeManager;
    this.span = span;
    this.source = source;
    // Register the continuation
    // It's fine to do it in (parent) constructor as the continuation instance is not used by the
    // trace collector
    this.span.context().getTraceCollector().registerContinuation(this);
  }

  protected abstract boolean tryActivate();

  protected abstract boolean tryCancel();

  @Override
  public AgentScope activate() {
    if (tryActivate()) {
      // If span is already current, cancel the continuation
      if (this.span.equals(this.scopeManager.activeSpan())) {
        cancel();
      }
      return this.scopeManager.activate(this.span, this.source, true);
    } else {
      return null;
    }
  }

  @Override
  public void cancel() {
    if (tryCancel()) {
      this.span.context().getTraceCollector().cancelContinuation(this);
    } else {
      LOGGER.debug("Failed to close continuation {}. Already used.", this);
    }
  }

  @Override
  public AgentSpan getSpan() {
    return this.span;
  }
}
