package datadog.trace.core.scopemanager;

import datadog.context.Context;
import datadog.context.ContextKey;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import javax.annotation.Nullable;

/** Wraps a {@link ScopeStack} as a {@link Context} so it can be swapped back later. */
final class ScopeContext implements Context {

  private static final AtomicReferenceFieldUpdater<ScopeContext, ScopeStack> SCOPE_STACK_UPDATER =
      AtomicReferenceFieldUpdater.newUpdater(ScopeContext.class, ScopeStack.class, "scopeStack");

  private final Context context;
  private volatile ScopeStack scopeStack;

  ScopeContext(ScopeStack scopeStack) {
    this(scopeStack, scopeStack.top != null ? scopeStack.top.context : Context.root());
  }

  private ScopeContext(ScopeStack scopeStack, Context context) {
    this.scopeStack = scopeStack;
    this.context = context;
  }

  ScopeStack restore() {
    // only restore original scope stack once; if we're asked to restore again use empty stack
    return SCOPE_STACK_UPDATER.getAndSet(this, scopeStack.empty());
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
