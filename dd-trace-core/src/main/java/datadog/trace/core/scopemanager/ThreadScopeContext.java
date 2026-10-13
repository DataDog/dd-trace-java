package datadog.trace.core.scopemanager;

import datadog.context.Context;
import datadog.context.ContextKey;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import javax.annotation.Nullable;

/**
 * Swaps a thread's own stack back in after a coroutine's shared stack ran on that thread. Unlike
 * {@link SavedScopeContext} it takes no snapshot, so one instance can be reused for every
 * coroutine.
 */
final class ThreadScopeContext extends ScopeStackContext {
  private final Thread originalThread = Thread.currentThread();
  private final ScopeStack scopeStack;

  ThreadScopeContext(ScopeStack scopeStack) {
    this.scopeStack = scopeStack;
  }

  @Override
  ScopeStack restore(ProfilingContextIntegration profilingContextIntegration) {
    // coroutines restore on the same thread; there's no snapshot to copy for any other thread
    if (Thread.currentThread() == originalThread) {
      return scopeStack;
    } else {
      return new ScopeStack(profilingContextIntegration);
    }
  }

  @Nullable
  @Override
  public <T> T get(ContextKey<T> key) {
    return contextOf(scopeStack.top()).get(key);
  }

  @Override
  public <T> Context with(ContextKey<T> key, @Nullable T value) {
    return contextOf(scopeStack.top()).with(key, value);
  }
}
