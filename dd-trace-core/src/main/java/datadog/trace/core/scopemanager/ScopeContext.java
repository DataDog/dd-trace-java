package datadog.trace.core.scopemanager;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import javax.annotation.Nullable;

/** Wraps a {@link ScopeStack} as a {@link Context} so it can be swapped back later. */
final class ScopeContext implements Context {
  private final Thread originalThread = Thread.currentThread();
  private final ScopeStack scopeStack;
  private final ContinuableScope parent;
  private final ContinuableScope active;
  private final Context context;

  ScopeContext(ScopeStack scopeStack) {
    this(scopeStack, scopeStack.top != null ? scopeStack.top.context : Context.root());
  }

  private ScopeContext(ScopeStack scopeStack, Context context) {
    this.scopeStack = scopeStack;
    this.parent = scopeStack.parent();
    this.active = scopeStack.active();
    this.context = context;
  }

  ScopeStack restore(ProfilingContextIntegration profilingContextIntegration) {
    // restore full stack on original thread, for other threads restore shallow copy
    if (Thread.currentThread() == originalThread) {
      return scopeStack;
    } else {
      return new ScopeStack(profilingContextIntegration, parent, active);
    }
  }

  @Nullable
  @Override
  public <T> T get(ContextKey<T> key) {
    return context.get(key);
  }

  @Override
  public <T> Context with(ContextKey<T> key, @Nullable T value) {
    return context.with(key, value);
  }
}
