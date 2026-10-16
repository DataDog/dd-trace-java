package datadog.trace.core.scopemanager;

import static datadog.trace.core.scopemanager.ContinuableScope.ITERATION;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import java.util.ArrayDeque;

/**
 * The invariant is that the top of a non-empty stack is always active. Anytime a scope is closed,
 * cleanup() is called to ensure the invariant
 */
class ScopeStack {

  private final ProfilingContextIntegration profilingContextIntegration;
  final ArrayDeque<ContinuableScope> stack; // previous scopes

  ContinuableScope top; // current scope

  // set by background task when a root iteration scope remains unclosed for too long
  volatile ContinuableScope overdueRootScope;

  ScopeStack(ProfilingContextIntegration profilingContextIntegration) {
    this.profilingContextIntegration = profilingContextIntegration;
    this.stack = new ArrayDeque<>();
  }

  /** Creates an empty stack with room for the given number of previous scopes. */
  ScopeStack(ProfilingContextIntegration profilingContextIntegration, int numPrevious) {
    this.profilingContextIntegration = profilingContextIntegration;
    this.stack = new ArrayDeque<>(numPrevious);
  }

  /** Restore a shallow stack for async propagation purposes. */
  ScopeStack(
      ProfilingContextIntegration profilingContextIntegration,
      ContinuableScope parent,
      ContinuableScope active) {
    this(profilingContextIntegration);
    if (parent != null) {
      stack.push(parent);
    }
    top = active;
  }

  ContinuableScope top() {
    return top;
  }

  ContinuableScope active() {
    // avoid attaching further spans to the root scope when it's been marked as overdue
    return top != overdueRootScope ? top : null;
  }

  ContinuableScope parent() {
    return stack.peek();
  }

  /** Removes and closes all scopes up to the nearest live scope */
  void cleanup() {
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
      top = null;
    } else if (changedTop) {
      top = curScope;
      if (curScope != null) {
        curScope.beforeActivated();
        curScope.afterActivated();
      }
    }
    if (top == null) {
      onBecomeEmpty();
    }
  }

  /** Marks a new scope as current, pushing the previous onto the stack */
  void push(final ContinuableScope scope) {
    scope.beforeActivated();
    if (top != null) {
      stack.push(top);
    } else {
      onBecomeNonEmpty();
    }
    top = scope;
    scope.afterActivated();
  }

  /** Fast check to see if the expectedScope is on top */
  boolean checkTop(final ContinuableScope expectedScope) {
    return expectedScope.equals(top);
  }

  /**
   * Slower check to see if overdue scopes ahead of the expected scope are all ITERATION scopes.
   * These represent iterations that are now out-of-scope and can be finished ready for cleanup.
   */
  boolean checkOverdueScopes(final ContinuableScope expectedScope) {
    // we already know 'top' isn't the expected scope, so just need to check its source
    if (top == null || top.source() != ITERATION) {
      return false;
    }
    // avoid calling close() as we're already in that method, instead just clear any
    // remaining references so the scope gets removed in the subsequent cleanup() call
    top.clearReferences();
    AgentSpan span = top.span();
    if (span != null) {
      span.finishWithEndToEnd();
    }
    // now do the same for any previous iteration scopes ahead of the expected scope
    for (ContinuableScope scope : stack) {
      if (scope.source() != ITERATION) {
        return expectedScope.equals(scope);
      } else {
        scope.clearReferences();
        span = scope.span();
        if (span != null) {
          span.finishWithEndToEnd();
        }
      }
    }
    return false; // we didn't find the expected scope
  }

  /** Returns the current depth, including the top scope */
  int depth() {
    return top != null ? 1 + stack.size() : 0;
  }

  /** Returns a context that swaps this stack back in. */
  ScopeStackContext swappedOut() {
    return new SavedScopeContext(this);
  }

  /** Returns a context that swaps the replaced stack back in, after this stack replaces it. */
  ScopeStackContext replacing(ScopeStack replaced) {
    return replaced.swappedOut();
  }

  private ThreadScopeContext sameThreadToken; // reused, as it takes no snapshot

  /**
   * Returns a reusable context that swaps this stack back in, for callers that always swap it back
   * on this thread.
   */
  ScopeStackContext swappedOutForSameThread() {
    ThreadScopeContext token = sameThreadToken;
    if (token == null) {
      sameThreadToken = token = new ThreadScopeContext(this);
    }
    return token;
  }

  // DQH - regrettably needed for pre-existing tests
  void clear() {
    stack.clear();
    top = null;
  }

  /** Notifies profiler that this thread has a context now */
  final void onBecomeNonEmpty() {
    try {
      profilingContextIntegration.onAttach();
    } catch (Throwable e) {
      ContinuableScopeManager.ratelimitedLog.warn("Unexpected profiling exception", e);
    }
  }

  /** Notifies profiler that this thread no longer has a context */
  final void onBecomeEmpty() {
    try {
      profilingContextIntegration.onDetach();
    } catch (Throwable e) {
      ContinuableScopeManager.ratelimitedLog.warn("Unexpected profiling exception", e);
    }
  }
}
