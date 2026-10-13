package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;

/**
 * A {@link ScopeStack} owned by a coroutine and shared by whichever threads are running it.
 *
 * <p>Threads normally hand the coroutine over in sequence, but the previous thread can still be
 * unwinding while the next one starts. Changes are therefore made under this stack's lock. The top
 * scope is also published through a volatile field so the frequent active-scope reads need no lock.
 */
final class SharedScopeStack extends ScopeStack {
  private volatile ContinuableScope liveTop;
  // written before liveTop, so it's visible to readers that read top() first
  private int liveDepth;

  /** Swaps this stack in on any thread; also returned when this stack is swapped out. */
  final SharedScopeContext handle;

  SharedScopeStack(ProfilingContextIntegration profilingContextIntegration) {
    super(profilingContextIntegration, 4);
    this.handle = new SharedScopeContext(this);
  }

  /** Seeds the stack with an initial scope, before it is shared with other threads. */
  void seed(ContinuableScope scope) {
    top = scope;
    liveDepth = 1;
    liveTop = scope;
  }

  @Override
  ContinuableScope top() {
    return liveTop;
  }

  @Override
  ContinuableScope active() {
    // avoid attaching further spans to the root scope when it's been marked as overdue
    ContinuableScope current = liveTop;
    return current != overdueRootScope ? current : null;
  }

  @Override
  synchronized ContinuableScope parent() {
    return super.parent();
  }

  /** Removes closed scopes from the top, such as scopes closed by another thread. */
  void cleanupClosedTop() {
    ContinuableScope current = liveTop;
    // only take the lock when there's something to remove
    if (current != null && !current.alive()) {
      synchronized (this) {
        if (top != null && !top.alive()) {
          cleanup();
        }
      }
    }
  }

  @Override
  ScopeStackContext swappedOut() {
    // the shared handle always reads the live stack, so there's no snapshot to take
    return handle;
  }

  @Override
  ScopeStackContext swappedOutForSameThread() {
    return handle;
  }

  @Override
  ScopeStackContext replacing(ScopeStack replaced) {
    // the coroutine always swaps the replaced stack back on this thread, so needs no snapshot
    return replaced.swappedOutForSameThread();
  }

  @Override
  synchronized void cleanup() {
    super.cleanup();
    liveDepth = super.depth();
    liveTop = top;
  }

  @Override
  synchronized void push(ContinuableScope scope) {
    super.push(scope);
    liveDepth = super.depth();
    liveTop = top;
  }

  @Override
  boolean checkTop(ContinuableScope expectedScope) {
    // only a fast check; closing re-checks the stack under the lock during cleanup
    return expectedScope.equals(liveTop);
  }

  @Override
  synchronized boolean checkOverdueScopes(ContinuableScope expectedScope) {
    return super.checkOverdueScopes(expectedScope);
  }

  @Override
  int depth() {
    // only used to enforce the depth limit, so a slightly stale value is fine
    return liveDepth;
  }

  @Override
  synchronized void clear() {
    super.clear();
    liveDepth = 0;
    liveTop = null;
  }
}
