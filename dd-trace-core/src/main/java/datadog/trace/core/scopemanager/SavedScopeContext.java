package datadog.trace.core.scopemanager;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

/** Wraps a {@link ScopeStack} as a {@link Context} so it can be swapped back later. */
final class SavedScopeContext extends ScopeStackContext {
  private final Thread originalThread = Thread.currentThread();
  private final ScopeStack scopeStack;
  private final ContinuableScope parent;
  private final ContinuableScope active;
  private final Context context;

  SavedScopeContext(ScopeStack scopeStack) {
    this(scopeStack, contextOf(scopeStack.top()));
  }

  private SavedScopeContext(ScopeStack scopeStack, Context context) {
    this.scopeStack = scopeStack;
    this.parent = scopeStack.parent();
    this.active = scopeStack.active();
    this.context = context;
  }

  @Override
  ScopeStack restore(ProfilingContextIntegration profilingContextIntegration) {
    // restore full stack on original thread, for other threads restore shallow copy
    if (Thread.currentThread() == originalThread) {
      return scopeStack;
    } else {
      return new ScopeStack(profilingContextIntegration, parent, active);
    }
  }

  @Override
  Context currentContext() {
    return context;
  }
}
