package datadog.trace.core.scopemanager;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

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

  @Override
  Context currentContext() {
    return contextOf(scopeStack.top());
  }
}
