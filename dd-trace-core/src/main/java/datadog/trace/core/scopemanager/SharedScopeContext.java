package datadog.trace.core.scopemanager;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import javax.annotation.Nullable;

/** Swaps the same {@link SharedScopeStack} onto every thread, instead of a copy. */
final class SharedScopeContext extends ScopeStackContext {
  private final SharedScopeStack sharedStack;

  SharedScopeContext(SharedScopeStack sharedStack) {
    this.sharedStack = sharedStack;
  }

  /** The context of the shared stack's current top scope. */
  Context currentContext() {
    return contextOf(sharedStack.top());
  }

  @Override
  ScopeStack restore(ProfilingContextIntegration profilingContextIntegration) {
    // drop any scopes closed elsewhere since this stack was last swapped in
    sharedStack.cleanupClosedTop();
    return sharedStack;
  }

  @Nullable
  @Override
  public <T> T get(ContextKey<T> key) {
    return currentContext().get(key);
  }

  @Override
  public <T> Context with(ContextKey<T> key, @Nullable T value) {
    return currentContext().with(key, value);
  }
}
