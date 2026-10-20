package datadog.trace.core.scopemanager;

import static datadog.trace.core.scopemanager.ContinuableScope.ITERATION;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ProfilingContextIntegration;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.ArrayList;
import java.util.List;

/**
 * A {@link ScopeStack} owned by a coroutine and shared by whichever threads are running it.
 *
 * <p>Threads normally hand the coroutine over in sequence, but the previous thread can still be
 * unwinding while the next one starts. Stack mutations take this stack's lock; reference-count
 * changes take the scope's lock. The top scope is published through a volatile field so the
 * frequent active-scope reads need no lock.
 *
 * <p>Scope listeners, profiler hooks and span finishing run outside the stack lock to avoid
 * deadlocks with application code. Notifications reread the live top, but legacy listeners receive
 * no context argument, so another worker can still change the active scope during notification.
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

  /** Removes closed scopes after swapping in; the swap activates the surviving top. */
  @Override
  void pruneClosedTop() {
    ContinuableScope current = liveTop;
    // only take the lock when there's something to remove
    if (current != null && !current.alive()) {
      cleanup(false);
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
  ScopeStackContext replacing(ScopeStack oldStack) {
    // Coroutines always swap the replaced stack back on this thread, so need no snapshot.
    return oldStack.swappedOutForSameThread();
  }

  @Override
  void cleanup() {
    cleanup(true);
  }

  // only reactivates the new top when asked, as swap() activates it after swapping in
  private void cleanup(boolean activateTop) {
    ContinuableScope closedScope;
    int closedCount = 0;
    synchronized (this) {
      ContinuableScope curScope = top;
      closedScope = curScope;
      while (curScope != null && !curScope.alive()) {
        closedCount++;
        curScope = stack.poll();
      }
      if (curScope != null && curScope == overdueRootScope) {
        // we know this scope is the last on the stack and is overdue
        // prevent a worker that already read the top from retaining it after removal
        curScope.clearReferences();
        closedCount++;
        overdueRootScope = null;
        curScope = null;
      }
      if (closedCount > 0) {
        publishTop(curScope);
      }
    }
    // Close callbacks carry no scope identity and use the same manager, so only a count is needed.
    for (int i = 0; i < closedCount; i++) {
      closedScope.onProperClose();
    }
    if (activateTop && closedCount > 0) {
      activateCurrentTop();
    }
    // A close listener may have opened another scope while notifications were running.
    if (liveTop == null) {
      onBecomeEmpty();
    }
  }

  @Override
  void push(ContinuableScope scope) {
    scope.markShared();
    boolean wasEmpty;
    synchronized (this) {
      wasEmpty = top == null;
      if (!wasEmpty) {
        stack.push(top);
      }
      publishTop(scope);
    }
    if (wasEmpty) {
      onBecomeNonEmpty();
    }
    activateCurrentTop();
  }

  @Override
  void activateCurrentTop() {
    ContinuableScope current = liveTop;
    if (current != null) {
      current.beforeActivated();
      // Profiler hooks can block or reenter; use the latest top for scope listeners.
      current = liveTop;
      if (current != null) {
        current.afterActivated();
      }
    }
  }

  @Override
  boolean checkTop(ContinuableScope expectedScope) {
    // only a fast check; closing re-checks the stack under the lock during cleanup
    return expectedScope.equals(liveTop);
  }

  @Override
  boolean checkOverdueScopes(ContinuableScope expectedScope) {
    List<AgentSpan> overdueSpans;
    boolean found = false;
    synchronized (this) {
      if (top == null || top.source() != ITERATION) {
        return false;
      }
      // Preserve ScopeStack's iteration checks, but defer finishing until after releasing the lock.
      overdueSpans = new ArrayList<>();
      top.clearReferences();
      AgentSpan span = top.span();
      if (span != null) {
        overdueSpans.add(span);
      }
      for (ContinuableScope scope : stack) {
        if (scope.source() != ITERATION) {
          found = expectedScope.equals(scope);
          break;
        }
        scope.clearReferences();
        span = scope.span();
        if (span != null) {
          overdueSpans.add(span);
        }
      }
    }
    for (AgentSpan span : overdueSpans) {
      span.finishWithEndToEnd();
    }
    return found;
  }

  @Override
  int depth() {
    // only used to enforce the depth limit, so a slightly stale value is fine
    return liveDepth;
  }
}
