package datadog.trace.core.scopemanager;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.Stateful;
import datadog.trace.api.scopemanager.ExtendedScopeListener;
import datadog.trace.api.scopemanager.ScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

class ContinuableScope implements ContextScope {

  // different sources of scopes
  static final byte INSTRUMENTATION = 0;
  static final byte MANUAL = 1;
  static final byte ITERATION = 2;
  static final byte CONTEXT = 3;

  private final ContinuableScopeManager scopeManager;

  final Context context; // package-private so scopeManager can access it directly

  private boolean asyncPropagating;

  private short checkpointCount = 0;

  private final byte source;

  private short referenceCount = 1;

  // set when pushed onto a coroutine's shared scope stack, which other threads can also update
  private boolean shared;

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
            "Tried to close {} scope when not on top.  Current top: {}", this, scopeStack.top());
      }

      byte source = source();
      scopeManager.healthMetrics.onScopeCloseError(source == MANUAL);
      if (source == MANUAL && scopeManager.strictMode) {
        throw new RuntimeException("Tried to close " + context + " scope when not on top");
      }
    }

    final boolean alive = release();
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
    boolean hasScopeListeners = !scopeManager.scopeListeners.isEmpty();
    boolean hasExtendedListeners = !scopeManager.extendedScopeListeners.isEmpty();
    if (!hasScopeListeners && !hasExtendedListeners) {
      return;
    }
    if (hasScopeListeners) {
      for (final ScopeListener listener : scopeManager.scopeListeners) {
        try {
          listener.afterScopeClosed();
        } catch (Exception e) {
          ContinuableScopeManager.log.debug("ScopeListener threw exception in close()", e);
        }
      }
    }
    if (hasExtendedListeners) {
      for (final ExtendedScopeListener listener : scopeManager.extendedScopeListeners) {
        try {
          listener.afterScopeClosed();
        } catch (Exception e) {
          ContinuableScopeManager.log.debug("ScopeListener threw exception in close()", e);
        }
      }
    }
  }

  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification =
          "Set before publishing the scope through SharedScopeStack.liveTop; the flag never "
              + "changes after publication.")
  final void markShared() {
    shared = true;
  }

  /** Adds a reference to a live scope -- returns false if the scope was already closed */
  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification =
          "Only non-shared scopes use the unlocked write; shared scopes retain under their "
              + "own monitor in retainShared().")
  final boolean retain() {
    if (shared) {
      return retainShared();
    }
    ++referenceCount;
    return true;
  }

  private synchronized boolean retainShared() {
    // another thread may have closed the scope since it was read from the shared stack
    if (referenceCount <= 0) {
      return false;
    }
    ++referenceCount;
    return true;
  }

  /** Removes a reference -- returns true if the scope is still alive */
  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification =
          "Only non-shared scopes use the unlocked write; shared scopes release under their "
              + "own monitor in releaseShared(), including closes from another stack.")
  final boolean release() {
    return shared ? releaseShared() : --referenceCount > 0;
  }

  private synchronized boolean releaseShared() {
    // the scope may be closed on any thread, whichever stack that thread has swapped in
    return --referenceCount > 0;
  }

  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification =
          "Only non-shared scopes use the unlocked write; shared scopes clear under their "
              + "own monitor in clearShared().")
  final void clearReferences() {
    if (shared) {
      clearShared();
    } else {
      referenceCount = 0;
    }
  }

  private synchronized void clearShared() {
    // stops another thread retaining the scope once it's been cleared
    referenceCount = 0;
  }

  /**
   * Returns true if the scope's reference count is positive.
   *
   * <p>For shared scopes, this unlocked read is a best-effort cleanup check. Once closed, a shared
   * scope cannot be retained again, so a stale read can delay cleanup but cannot cause a live scope
   * to be removed.
   */
  final boolean alive() {
    return referenceCount > 0;
  }

  public final boolean isAsyncPropagating() {
    return asyncPropagating;
  }

  public final AgentSpan span() {
    return AgentSpan.fromContext(context);
  }

  @Override
  public Context context() {
    return context;
  }

  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification =
          "Changes are serialized with scope execution, including coroutine handoff between "
              + "workers. Overlapping scope close/restore callbacks do not modify this flag.")
  public final void setAsyncPropagation(final boolean value) {
    asyncPropagating = value;
  }

  @Override
  public final String toString() {
    return super.toString() + "->" + context;
  }

  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification =
          "Checkpoint updates are serialized with scope execution; coroutine handoff orders "
              + "them between workers. Overlapping scope close/restore does not modify checkpoints.")
  public void checkpoint() {
    checkpointCount++;
  }

  @SuppressFBWarnings(
      value = "AT_STALE_THREAD_WRITE_OF_PRIMITIVE",
      justification =
          "Checkpoint updates are serialized with scope execution; coroutine handoff orders "
              + "them between workers. Overlapping scope close/restore does not modify checkpoints.")
  public boolean rollback() {
    if (checkpointCount > 0) {
      checkpointCount--;
      return false; // stop rollback at checkpoint
    } else {
      return true;
    }
  }

  public final void beforeActivated() {
    if (scopeState == Stateful.DEFAULT) {
      return;
    }
    AgentSpan span = span();
    if (span == null) {
      return;
    }
    try {
      scopeState.activate(span.spanContext());
    } catch (Throwable e) {
      ContinuableScopeManager.ratelimitedLog.warn(
          "ScopeState {} threw exception in beforeActivated()", scopeState.getClass(), e);
    }
  }

  public final void afterActivated() {
    boolean hasScopeListeners = !scopeManager.scopeListeners.isEmpty();
    boolean hasExtendedListeners = !scopeManager.extendedScopeListeners.isEmpty();
    if (!hasScopeListeners && !hasExtendedListeners) {
      return;
    }
    AgentSpan span = span();
    if (span == null) {
      return;
    }
    if (hasScopeListeners) {
      for (final ScopeListener listener : scopeManager.scopeListeners) {
        try {
          listener.afterScopeActivated();
        } catch (Throwable e) {
          ContinuableScopeManager.log.debug("ScopeListener threw exception in afterActivated()", e);
        }
      }
    }
    if (hasExtendedListeners) {
      for (final ExtendedScopeListener listener : scopeManager.extendedScopeListeners) {
        try {
          listener.afterScopeActivated(span.getTraceId(), span.getSpanId());
        } catch (Throwable e) {
          ContinuableScopeManager.log.debug(
              "ExtendedScopeListener threw exception in afterActivated()", e);
        }
      }
    }
  }

  public byte source() {
    return (byte) (source & 0x7F);
  }
}
