package datadog.trace.core.scopemanager;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.ScopeState;

public class ContextBasedScopeState implements ScopeState {
  private Context context;

  ContextBasedScopeState() {
    this.context = Context.empty();
  }

  @Override
  public void activate() {
    // TODO This creates a context scope
    // Should we able to restore context while bypassing scope creation?
    this.context.makeCurrent();
  }

  @Override
  public void fetchFromActive() {
    this.context = Context.current();
  }
}
