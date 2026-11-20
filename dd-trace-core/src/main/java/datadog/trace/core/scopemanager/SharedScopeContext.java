package datadog.trace.core.scopemanager;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

/** Swaps the same {@link SharedScopeStack} onto every thread, instead of a copy. */
final class SharedScopeContext extends ScopeStackContext {
  private final SharedScopeStack sharedStack;

  SharedScopeContext(SharedScopeStack sharedStack) {
    this.sharedStack = sharedStack;
  }

  /** The context of the shared stack's current top scope. */
  @Override
  Context currentContext() {
    return contextOf(sharedStack.top());
  }

  @Override
  ScopeStack restore(ProfilingContextIntegration profilingContextIntegration) {
    return sharedStack;
  }
}
