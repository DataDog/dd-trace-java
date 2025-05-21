package datadog.trace.core.scopemanager;

import datadog.context.Context;
import datadog.context.ContextKey;
import javax.annotation.Nullable;

/** Wraps a {@link ScopeStack} as a {@link Context} so it can be swapped back later. */
final class ScopeContext implements Context {
  final ScopeStack scopeStack;
  private final Context context;

  ScopeContext(ScopeStack scopeStack) {
    this(scopeStack, scopeStack.top != null ? scopeStack.top.context : Context.root());
  }

  private ScopeContext(ScopeStack scopeStack, Context context) {
    this.scopeStack = scopeStack;
    this.context = context;
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
