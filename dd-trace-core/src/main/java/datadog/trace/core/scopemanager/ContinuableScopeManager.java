package datadog.trace.core.scopemanager;

import static datadog.trace.api.ConfigDefaults.DEFAULT_ASYNC_PROPAGATING;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopAgentSpan;

import datadog.trace.api.StatsDClient;
import datadog.trace.api.scopemanager.ExtendedScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.context.ScopeListener;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The primary ScopeManager. This class has ownership of the core ThreadLocal containing the
 * currently active Scope. Such scopes can be suspended with a Continuation to prevent the trace
 * from being reported even if all related spans are finished. It also delegates to other
 * ScopeInterceptors to provide additional functionality.
 */
public final class ContinuableScopeManager implements AgentScopeManager {

  static final Logger log = LoggerFactory.getLogger(ContinuableScopeManager.class);
  final ThreadLocal<ScopeStack> tlsScopeStack =
      new ThreadLocal<ScopeStack>() {
        @Override
        protected final ScopeStack initialValue() {
          return new ScopeStack();
        }
      };

  final List<ScopeListener> scopeListeners;
  final List<ExtendedScopeListener> extendedScopeListeners;
  final StatsDClient statsDClient;

  private final int depthLimit;
  private final boolean strictMode;
  private final boolean inheritAsyncPropagation;

  public ContinuableScopeManager(
      final int depthLimit,
      final StatsDClient statsDClient,
      final boolean strictMode,
      final boolean inheritAsyncPropagation) {

    this.depthLimit = depthLimit == 0 ? Integer.MAX_VALUE : depthLimit;
    this.statsDClient = statsDClient;
    this.strictMode = strictMode;
    this.inheritAsyncPropagation = inheritAsyncPropagation;
    this.scopeListeners = new CopyOnWriteArrayList<>();
    this.extendedScopeListeners = new CopyOnWriteArrayList<>();
  }

  @Override
  public AgentScope activate(final AgentSpan span, final ScopeSource source) {
    return activate(span, source.id(), false, /* ignored */ false);
  }

  @Override
  public AgentScope activate(
      final AgentSpan span, final ScopeSource source, boolean isAsyncPropagating) {
    return activate(span, source.id(), true, isAsyncPropagating);
  }

  @Override
  public AgentScope.Continuation captureSpan(final AgentSpan span, final ScopeSource source) {
    Continuation continuation = new SingleContinuation(this, span, source.id());
    continuation.register();
    return continuation;
  }

  private AgentScope activate(
      final AgentSpan span,
      final byte source,
      final boolean overrideAsyncPropagation,
      final boolean isAsyncPropagating) {
    ScopeStack scopeStack = scopeStack();

    final ContinuableScope active = scopeStack.top();
    if (active != null && active.span.equals(span)) {
      active.incrementReferences();
      return active;
    }

    // DQH - This check could go before the check above, since depth limit checking is fast
    final int currentDepth = scopeStack.depth();
    if (depthLimit <= currentDepth) {
      log.debug("Scope depth limit exceeded ({}).  Returning NoopScope.", currentDepth);
      return AgentTracer.NoopAgentScope.INSTANCE;
    }

    assert span != null;

    // Inherit the async propagation from the active scope unless the value is overridden
    boolean asyncPropagation =
        overrideAsyncPropagation
            ? isAsyncPropagating
            : inheritAsyncPropagation && active != null
                ? active.isAsyncPropagating()
                : DEFAULT_ASYNC_PROPAGATING;

    final ContinuableScope scope = new ContinuableScope(this, span, source, asyncPropagation);

    scopeStack.push(scope);

    return scope;
  }

  /**
   * Creates a new scope when a {@link Continuation} is activated.
   *
   * @param continuation {@code null} if a continuation is re-used
   */
  ContinuableScope continueSpan(
      final Continuation continuation, final AgentSpan span, final byte source) {

    final ContinuableScope scope;
    if (continuation != null) {
      scope = new ContinuingScope(this, span, source, true, continuation);
    } else {
      scope = new ContinuableScope(this, span, source, true);
    }

    scopeStack().push(scope);

    return scope;
  }

  @Override
  public AgentScope active() {
    return scopeStack().top();
  }

  @Override
  public AgentSpan activeSpan() {
    final ContinuableScope active = scopeStack().top();
    return active == null ? null : active.span;
  }

  /** Attach a listener to scope activation events */
  public void addScopeListener(final ScopeListener listener) {
    if (listener instanceof ExtendedScopeListener) {
      addExtendedScopeListener((ExtendedScopeListener) listener);
    } else {
      scopeListeners.add(listener);
      log.debug("Added scope listener {}", listener);
      AgentSpan activeSpan = activeSpan();
      if (activeSpan != null) {
        // Notify the listener about the currently active scope
        listener.afterScopeActivated();
      }
    }
  }

  private void addExtendedScopeListener(final ExtendedScopeListener listener) {
    extendedScopeListeners.add(listener);
    log.debug("Added scope listener {}", listener);
    AgentSpan activeSpan = activeSpan();
    if (activeSpan != null && !(activeSpan instanceof NoopAgentSpan)) {
      // Notify the listener about the currently active scope
      listener.afterScopeActivated(activeSpan.getTraceId(), activeSpan.context().getSpanId());
    }
  }

  ScopeStack scopeStack() {
    return this.tlsScopeStack.get();
  }

  private static class ContinuableScope implements AgentScope {
    private final ContinuableScopeManager scopeManager;

    final AgentSpan span; // package-private so scopeManager can access it directly

    /** Flag to propagate this scope across async boundaries. */
    private boolean isAsyncPropagating;

    private byte flags;

    private short referenceCount = 1;

    ContinuableScope(
        final ContinuableScopeManager scopeManager,
        final AgentSpan span,
        final byte source,
        final boolean isAsyncPropagating) {
      this.scopeManager = scopeManager;
      this.span = span;
      this.flags = source;
      this.isAsyncPropagating = isAsyncPropagating;
    }

    @Override
    public final void close() {
      final ScopeStack scopeStack = scopeManager.scopeStack();

      final boolean onTop = scopeStack.checkTop(this);
      if (!onTop) {
        if (log.isDebugEnabled()) {
          log.debug(
              "Tried to close {} scope when not on top.  Current top: {}", this, scopeStack.top());
        }

        scopeManager.statsDClient.incrementCounter("scope.close.error");

        if (source() == ScopeSource.MANUAL.id()) {
          scopeManager.statsDClient.incrementCounter("scope.user.close.error");

          if (scopeManager.strictMode) {
            throw new RuntimeException("Tried to close scope when not on top");
          }
        }
      }

      final boolean alive = decrementReferences();
      if (!alive) {
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
          log.debug("ScopeListener threw exception in close()", e);
        }
      }

      if (!notifiedOnActivate()) {
        return;
      }

      for (final ExtendedScopeListener listener : scopeManager.extendedScopeListeners) {
        try {
          listener.afterScopeClosed();
        } catch (Exception e) {
          log.debug("ScopeListener threw exception in close()", e);
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

    /** Returns true if the scope is still alive (non-zero ref count) */
    final boolean alive() {
      return referenceCount > 0;
    }

    @Override
    public final boolean isAsyncPropagating() {
      return isAsyncPropagating;
    }

    @Override
    public final AgentSpan span() {
      return span;
    }

    @Override
    public final void setAsyncPropagation(final boolean value) {
      isAsyncPropagating = value;
    }

    @Override
    public boolean checkpointed() {
      return false;
    }

    /**
     * The continuation returned must be closed or activated or the trace will not finish.
     *
     * @return The new continuation, or null if this scope is not async propagating.
     */
    @Override
    public final ContinuableScopeManager.Continuation capture() {
      return isAsyncPropagating
          ? new SingleContinuation(scopeManager, span, source()).register()
          : null;
    }

    /**
     * The continuation returned must be closed or activated or the trace will not finish.
     *
     * @return The new continuation, or null if this scope is not async propagating.
     */
    @Override
    public final ContinuableScopeManager.Continuation captureConcurrent() {
      return isAsyncPropagating
          ? new ConcurrentContinuation(scopeManager, span, source()).register()
          : null;
    }

    @Override
    public final String toString() {
      return super.toString() + "->" + span;
    }

    public final void afterActivated() {
      for (final ScopeListener listener : scopeManager.scopeListeners) {
        try {
          listener.afterScopeActivated();
        } catch (Throwable e) {
          log.debug("ScopeListener threw exception in afterActivated()", e);
        }
      }

      if (span.eligibleForDropping()) {
        return;
      }
      flags |= 0x80;

      for (final ExtendedScopeListener listener : scopeManager.extendedScopeListeners) {
        try {
          listener.afterScopeActivated(span.getTraceId(), span.context().getSpanId());
        } catch (Throwable e) {
          log.debug("ExtendedScopeListener threw exception in afterActivated()", e);
        }
      }
    }

    private byte source() {
      return (byte) (flags & 0x7F);
    }

    private boolean notifiedOnActivate() {
      return flags < 0;
    }
  }

  private static final class ContinuingScope extends ContinuableScope {
    /** Continuation that created this scope. */
    private final ContinuableScopeManager.Continuation continuation;

    ContinuingScope(
        final ContinuableScopeManager scopeManager,
        final AgentSpan span,
        final byte source,
        final boolean isAsyncPropagating,
        final ContinuableScopeManager.Continuation continuation) {
      super(scopeManager, span, source, isAsyncPropagating);
      this.continuation = continuation;
    }

    @Override
    public boolean checkpointed() {
      return continuation.migrated;
    }

    @Override
    void cleanup(final ScopeStack scopeStack) {
      super.cleanup(scopeStack);

      continuation.cancelFromContinuedScopeClose();
    }
  }

  /**
   * The invariant is that the top of a non-empty stack is always active. Anytime a scope is closed,
   * cleanup() is called to ensure the invariant
   */
  static final class ScopeStack {
    private final ArrayDeque<ContinuableScope> stack = new ArrayDeque<>();

    /** top - accesses the top of the ScopeStack */
    ContinuableScope top() {
      return stack.peek();
    }

    void cleanup() {
      ContinuableScope curScope = stack.peek();
      boolean changedTop = false;
      while (curScope != null) {
        if (curScope.alive()) {
          if (changedTop) {
            curScope.afterActivated();
          }
          break;
        }

        // no longer alive -- trigger listener & null out
        curScope.onProperClose();
        stack.poll();
        changedTop = true;
        curScope = stack.peek();
      }
    }

    /** Pushes a new scope unto the stack */
    void push(final ContinuableScope scope) {
      stack.push(scope);
      scope.afterActivated();
    }

    /** Fast check to see if the expectedScope is on top the stack */
    boolean checkTop(final ContinuableScope expectedScope) {
      return expectedScope.equals(stack.peek());
    }

    /** Returns the current stack depth */
    int depth() {
      return stack.size();
    }

    // DQH - regrettably needed for pre-existing tests
    void clear() {
      stack.clear();
    }
  }

  /**
   * This class must not be a nested class of ContinuableScope to avoid an unconstrained chain of
   * references (using too much memory).
   */
  private abstract static class Continuation implements AgentScope.Continuation {

    final ContinuableScopeManager scopeManager;
    final AgentSpan spanUnderScope;
    final byte source;
    final AgentTrace trace;
    protected volatile boolean migrated;

    public Continuation(
        ContinuableScopeManager scopeManager, AgentSpan spanUnderScope, byte source) {
      this.scopeManager = scopeManager;
      this.spanUnderScope = spanUnderScope;
      this.source = source;
      this.trace = spanUnderScope.context().getTrace();
    }

    Continuation register() {
      trace.registerContinuation(this);
      return this;
    }

    // Called by ContinuableScopeManager when a continued scope is closed
    // Can't use cancel() for SingleContinuation because of the "used" check
    abstract void cancelFromContinuedScopeClose();
  }

  /**
   * This class must not be a nested class of ContinuableScope to avoid an unconstrained chain of
   * references (using too much memory).
   */
  private static final class SingleContinuation extends Continuation {
    private static final AtomicIntegerFieldUpdater<SingleContinuation> USED =
        AtomicIntegerFieldUpdater.newUpdater(SingleContinuation.class, "used");
    private volatile int used = 0;

    private SingleContinuation(
        final ContinuableScopeManager scopeManager,
        final AgentSpan spanUnderScope,
        final byte source) {
      super(scopeManager, spanUnderScope, source);
    }

    @Override
    public AgentScope activate() {
      if (USED.compareAndSet(this, 0, 1)) {
        if (migrated) {
          spanUnderScope.finishThreadMigration();
        }
        return scopeManager.continueSpan(this, spanUnderScope, source);
      } else {
        log.debug(
            "Failed to activate continuation. Reusing a continuation not allowed. Spans may be reported separately.");
        return scopeManager.continueSpan(null, spanUnderScope, source);
      }
    }

    @Override
    public void cancel() {
      if (USED.compareAndSet(this, 0, 1)) {
        trace.cancelContinuation(this);
      } else {
        log.debug("Failed to close continuation {}. Already used.", this);
      }
    }

    @Override
    public void migrate() {
      this.migrated = true;
      spanUnderScope.startThreadMigration();
    }

    @Override
    public void migrated() {
      this.migrated = true;
    }

    @Override
    public AgentSpan getSpan() {
      return spanUnderScope;
    }

    @Override
    void cancelFromContinuedScopeClose() {
      trace.cancelContinuation(this);
    }

    @Override
    public String toString() {
      return getClass().getSimpleName()
          + "@"
          + Integer.toHexString(hashCode())
          + "->"
          + spanUnderScope;
    }
  }

  /**
   * This class must not be a nested class of ContinuableScope to avoid an unconstrained chain of
   * references (using too much memory).
   *
   * <p>This {@link Continuation} differs from the {@link SingleContinuation} in that if it is
   * activated, it needs to be canceled in addition to the returned {@link AgentScope} being closed.
   * This is to allow multiple concurrent threads that activate the continuation to race in a safe
   * way, and close the scopes without fear of closing the related {@link AgentSpan} prematurely.
   */
  private static final class ConcurrentContinuation extends Continuation {
    private static final int START = 1;
    private static final int CLOSED = Integer.MIN_VALUE >> 1;
    private static final int BARRIER = Integer.MIN_VALUE >> 2;
    private volatile int count = START;

    private static final AtomicIntegerFieldUpdater<ConcurrentContinuation> COUNT =
        AtomicIntegerFieldUpdater.newUpdater(ConcurrentContinuation.class, "count");

    private ConcurrentContinuation(
        final ContinuableScopeManager scopeManager,
        final AgentSpan spanUnderScope,
        final byte source) {
      super(scopeManager, spanUnderScope, source);
      spanUnderScope.startThreadMigration();
    }

    private boolean tryActivate() {
      int current = COUNT.incrementAndGet(this);
      if (current < START) {
        COUNT.decrementAndGet(this);
      }
      return current > START;
    }

    private boolean tryClose() {
      int current = COUNT.get(this);
      if (current < BARRIER) {
        return false;
      }
      // Now decrement the counter
      current = COUNT.decrementAndGet(this);
      // Try to close this if we are between START and BARRIER
      while (current < START && current > BARRIER) {
        if (COUNT.compareAndSet(this, current, CLOSED)) {
          return true;
        }
        current = COUNT.get(this);
      }
      return false;
    }

    @Override
    public AgentScope activate() {
      if (tryActivate()) {
        AgentScope scope = scopeManager.continueSpan(this, spanUnderScope, source);
        spanUnderScope.finishThreadMigration();
        return scope;
      } else {
        return null;
      }
    }

    @Override
    public void cancel() {
      if (tryClose()) {
        trace.cancelContinuation(this);
      }
      log.debug("t_id={} -> canceling continuation {}", spanUnderScope.getTraceId(), this);
    }

    @Override
    public void migrate() {
      // This has no meaning for a concurrent continuation
    }

    @Override
    public void migrated() {
      // This has no meaning for a concurrent continuation
    }

    @Override
    public AgentSpan getSpan() {
      return spanUnderScope;
    }

    @Override
    void cancelFromContinuedScopeClose() {
      spanUnderScope.finishWork();
      cancel();
    }

    @Override
    public String toString() {
      int c = COUNT.get(this);
      String s = c < BARRIER ? "CANCELED" : String.valueOf(c);
      return getClass().getSimpleName()
          + "@"
          + Integer.toHexString(hashCode())
          + "("
          + s
          + ")->"
          + spanUnderScope;
    }
  }
}
