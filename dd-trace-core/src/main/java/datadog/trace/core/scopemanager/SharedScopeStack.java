package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * A {@link ScopeStack} owned by a coroutine and shared by whichever threads are running it.
 *
 * <p>Threads normally hand the coroutine over in sequence, but the previous thread can still be
 * unwinding while the next one starts. Stack mutations take this stack's lock; reference-count
 * changes take the scope's lock. The top scope is published through a volatile field so the
 * frequent active-scope reads need no lock.
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
    scope.markShared();
    publishTop(scope);
  }

  /** Updates the top scope, publishing it to lock-free readers before any listeners run. */
  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification =
          "The following volatile liveTop write publishes liveDepth to readers that acquire "
              + "the top. Depth is a best-effort limit check, so concurrent staleness is acceptable.")
  private void publishTop(ContinuableScope scope) {
    top = scope;
    liveDepth = scope != null ? 1 + stack.size() : 0;
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

  // copies ScopeStack.cleanup(), so listeners see the new top when it's reactivated
  @Override
  synchronized void cleanup() {
    ContinuableScope curScope = top;
    boolean changedTop = false;
    while (curScope != null && !curScope.alive()) {
      // no longer alive -- trigger listener & null out
      curScope.onProperClose();
      changedTop = true;
      curScope = stack.poll();
    }
    if (curScope != null && curScope == overdueRootScope) {
      // we know this scope is the last on the stack and is overdue
      curScope.onProperClose();
      overdueRootScope = null;
      publishTop(null);
    } else if (changedTop) {
      publishTop(curScope);
      if (curScope != null) {
        curScope.beforeActivated();
        curScope.afterActivated();
      }
    }
    if (top == null) {
      onBecomeEmpty();
    }
  }

  // copies ScopeStack.push(), so listeners see the new top when it's activated
  @Override
  synchronized void push(ContinuableScope scope) {
    scope.markShared();
    scope.beforeActivated();
    if (top != null) {
      stack.push(top);
    } else {
      onBecomeNonEmpty();
    }
    publishTop(scope);
    scope.afterActivated();
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
    stack.clear();
    publishTop(null);
  }
}
