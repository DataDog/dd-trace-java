package datadog.trace.core.scopemanager;

import datadog.context.Context;
import datadog.trace.api.Stateful;
import datadog.trace.api.scopemanager.ExtendedScopeListener;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

class ContinuableScope implements AgentScope {

  // different sources of scopes
  static final byte INSTRUMENTATION = 0;
  static final byte MANUAL = 1;
  static final byte ITERATION = 2;
  static final byte CONTEXT = 3;

  private final ContinuableScopeManager scopeManager;

  final Context context; // package-private so scopeManager can access it directly

  private boolean asyncPropagating;

  private final byte source;

  private short referenceCount = 1;

  private final Stateful scopeState;

  ContinuableScope(
      final ContinuableScopeManager scopeManager,
      final Context context,
      final byte source,
      final boolean asyncPropagating,
      final Stateful scopeState) {
    this.scopeManager = scopeManager;
    this.context = context;
    this.source = source;
    this.asyncPropagating = asyncPropagating;
    this.scopeState = scopeState;
  }

  @Override
  public final void close() {
    final ScopeStack scopeStack = scopeManager.scopeStack();

    // fast check first, only perform slower check when there's an inconsistency with the stack
    if (!scopeStack.checkTop(this) && !scopeStack.checkOverdueScopes(this)) {
      if (ContinuableScopeManager.log.isDebugEnabled()) {
        ContinuableScopeManager.log.debug(
            "Tried to close {} scope when not on top.  Current top: {}", this, scopeStack.top);
      }

      byte source = source();
      scopeManager.healthMetrics.onScopeCloseError(source == MANUAL);
      if (source == MANUAL && scopeManager.strictMode) {
        throw new RuntimeException("Tried to close " + context + " scope when not on top");
      }
    }

    final boolean alive = decrementReferences();
    scopeManager.healthMetrics.onCloseScope();
    if (!alive) {
      scopeState.close();
      cleanup(scopeStack);
    }
  }

  void cleanup(final ScopeStack scopeStack) {
    scopeStack.cleanup();
  }

  /*
   * Exists to allow stack unwinding to do a delayed call to close when the close is
   * finished properly.  e.g. When the scope is back on the top of the stack.
   *
   * DQH - If we clean-up the delegation code & notification semantics at later time,
   * I would hope this becomes unnecessary.
   */
  final void onProperClose() {
    for (final ScopeListener listener : scopeManager.scopeListeners) {
      try {
        listener.afterScopeClosed();
      } catch (Exception e) {
        ContinuableScopeManager.log.debug("ScopeListener threw exception in close()", e);
      }
    }

    for (final ExtendedScopeListener listener : scopeManager.extendedScopeListeners) {
      try {
        listener.afterScopeClosed();
      } catch (Exception e) {
        ContinuableScopeManager.log.debug("ScopeListener threw exception in close()", e);
      }
    }
  }

  final void incrementReferences() {
    ++referenceCount;
  }

  /** Decrements ref count -- returns true if the scope is still alive */
  final boolean decrementReferences() {
    return --referenceCount > 0;
  }

  final void clearReferences() {
    referenceCount = 0;
  }

  /** Returns true if the scope is still alive (non-zero ref count) */
  final boolean alive() {
    return referenceCount > 0;
  }

  @Override
  public final boolean isAsyncPropagating() {
    return asyncPropagating;
  }

  @Override
  public final AgentSpan span() {
    return AgentSpan.fromContext(context);
  }

  @Override
  public Context context() {
    return context;
  }

  @Override
  public final void setAsyncPropagation(final boolean value) {
    asyncPropagating = value;
  }

  @Override
  public final String toString() {
    return super.toString() + "->" + context;
  }

  public final void beforeActivated() {
    AgentSpan span = span();
    if (span == null) {
      return;
    }
    try {
      scopeState.activate(span.context());
    } catch (Throwable e) {
      ContinuableScopeManager.ratelimitedLog.warn(
          "ScopeState {} threw exception in beforeActivated()", scopeState.getClass(), e);
    }
  }

  public final void afterActivated() {
    AgentSpan span = span();
    if (span == null) {
      return;
    }
    for (final ScopeListener listener : scopeManager.scopeListeners) {
      try {
        listener.afterScopeActivated();
      } catch (Throwable e) {
        ContinuableScopeManager.log.debug("ScopeListener threw exception in afterActivated()", e);
      }
    }

    for (final ExtendedScopeListener listener : scopeManager.extendedScopeListeners) {
      try {
        listener.afterScopeActivated(span.getTraceId(), span.getSpanId());
      } catch (Throwable e) {
        ContinuableScopeManager.log.debug(
            "ExtendedScopeListener threw exception in afterActivated()", e);
      }
    }
  }

  public byte source() {
    return (byte) (source & 0x7F);
  }
}
