package datadog.trace.core.scopemanager;

import static datadog.trace.core.scopemanager.ContextBasedScopeManager.LOGGER;

import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import java.util.concurrent.atomic.AtomicBoolean;

public class ContextBasedContinuation extends ContextBasedAbstractContinuation {
  /** A flag to mark when the continuation is activated or cancelled to avoid multiple usages. */
  private final AtomicBoolean used;

  ContextBasedContinuation(AgentScopeManager scopeManager, AgentSpan span, ScopeSource source) {
    super(scopeManager, span, source);
    this.used = new AtomicBoolean(false);
  }

  protected boolean tryActivate() {
    if (!this.used.compareAndSet(false, true)) {
      LOGGER.debug(
          "Failed to activate continuation. Reusing a continuation not allowed. Spans may be reported separately.");
    }
    return true;
  }

  protected boolean tryCancel() {
    if (this.used.compareAndSet(false, true)) {
      return true;
    } else {
      LOGGER.debug("Failed to close continuation {}. Already used.", this);
      return false;
    }
  }
}
