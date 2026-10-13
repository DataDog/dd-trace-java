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

  SharedScopeStack(ProfilingContextIntegration profilingContextIntegration) {
    super(profilingContextIntegration, 4);
  }

  /** Seeds the stack with an initial scope, before it is shared with other threads. */
  void seed(ContinuableScope scope) {
    top = scope;
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
  synchronized void cleanupClosedTop() {
    if (top != null && !top.alive()) {
      cleanup();
    }
  }

  @Override
  synchronized void cleanup() {
    super.cleanup();
    liveTop = top;
  }

  @Override
  synchronized void push(ContinuableScope scope) {
    super.push(scope);
    liveTop = top;
  }

  @Override
  synchronized boolean checkTop(ContinuableScope expectedScope) {
    return super.checkTop(expectedScope);
  }

  @Override
  synchronized boolean checkOverdueScopes(ContinuableScope expectedScope) {
    return super.checkOverdueScopes(expectedScope);
  }

  @Override
  synchronized int depth() {
    return super.depth();
  }

  @Override
  synchronized void clear() {
    super.clear();
    liveTop = null;
  }
}
