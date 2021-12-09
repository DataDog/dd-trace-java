package datadog.trace.core.scopemanager;

import static datadog.trace.api.ConfigDefaults.DEFAULT_ASYNC_PROPAGATING;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.NoopAgentSpan;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import datadog.trace.api.Config;
import datadog.trace.api.StatsDClient;
import datadog.trace.api.scopemanager.ExtendedScopeListener;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.context.ScopeListener;
import datadog.trace.util.AgentTaskScheduler;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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

  static final long iterationKeepAlive =
      SECONDS.toMillis(Config.get().getScopeIterationKeepAlive());

  volatile ConcurrentMap<ScopeStack, ContinuableScope> rootIterationScopes;

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

    final ContinuableScope top = scopeStack.top;
    if (top != null && top.span.equals(span)) {
      top.incrementReferences();
      return top;
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
            : inheritAsyncPropagation && top != null
                ? top.isAsyncPropagating()
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
  public void closePrevious(final boolean finishSpan) {
    ScopeStack scopeStack = scopeStack();

    // close any immediately previous iteration scope
    final ContinuableScope top = scopeStack.top;
    if (top != null && top.source() == ScopeSource.ITERATION.id()) {
      if (iterationKeepAlive > 0) { // skip depth check because cancelling is cheap
        cancelRootIterationScopeCleanup(scopeStack, top);
      }
      top.close();
      scopeStack.cleanup();
      if (finishSpan) {
        top.span.finishEndToEnd();
      }
    }
  }

  @Override
  public AgentScope activateNext(final AgentSpan span) {
    ScopeStack scopeStack = scopeStack();

    final int currentDepth = scopeStack.depth();
    if (depthLimit <= currentDepth) {
      log.debug("Scope depth limit exceeded ({}).  Returning NoopScope.", currentDepth);
      return AgentTracer.NoopAgentScope.INSTANCE;
    }

    assert span != null;

    final ContinuableScope top = scopeStack.top;

    boolean asyncPropagation =
        inheritAsyncPropagation && top != null
            ? top.isAsyncPropagating()
            : DEFAULT_ASYNC_PROPAGATING;

    final ContinuableScope scope =
        new ContinuableScope(this, span, ScopeSource.ITERATION.id(), asyncPropagation);

    if (iterationKeepAlive > 0 && currentDepth == 0) {
      // no surrounding scope to aid cleanup, so use background task instead
      scheduleRootIterationScopeCleanup(scopeStack, scope);
    }

    scopeStack.push(scope);

    return scope;
  }

  @Override
  public AgentScope active() {
    return scopeStack().active();
  }

  @Override
  public AgentSpan activeSpan() {
    final ContinuableScope active = scopeStack().active();
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

      // fast check first, only perform slower check when there's an inconsistency with the stack
      if (!scopeStack.checkTop(this) && !scopeStack.checkOverdueScopes(this)) {
        if (log.isDebugEnabled()) {
          log.debug(
              "Tried to close {} scope when not on top.  Current top: {}", this, scopeStack.top);
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

    final void clearReferences() {
      referenceCount = 0;
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

    @Override
    public byte source() {
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
    private final ArrayDeque<ContinuableScope> stack = new ArrayDeque<>(); // previous scopes

    ContinuableScope top; // current scope

    // set by background task when a root iteration scope remains unclosed for too long
    volatile ContinuableScope overdueRootScope;

    ContinuableScope active() {
      // avoid attaching further spans to the root scope when it's been marked as overdue
      return top != overdueRootScope ? top : null;
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
          curScope.afterActivated();
        }
      }
    }

    /** Marks a new scope as current, pushing the previous onto the stack */
    void push(final ContinuableScope scope) {
      if (top != null) {
        stack.push(top);
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
    final boolean checkOverdueScopes(final ContinuableScope expectedScope) {
      // we already know 'top' isn't the expected scope, so just need to check its source
      if (top == null || top.source() != ScopeSource.ITERATION.id()) {
        return false;
      }
      // avoid calling close() as we're already in that method, instead just clear any
      // remaining references so the scope gets removed in the subsequent cleanup() call
      top.clearReferences();
      top.span.finishEndToEnd();
      // now do the same for any previous iteration scopes ahead of the expected scope
      for (ContinuableScope scope : stack) {
        if (scope.source() != ScopeSource.ITERATION.id()) {
          return expectedScope.equals(scope);
        } else {
          scope.clearReferences();
          scope.span.finishEndToEnd();
        }
      }
      return false; // we didn't find the expected scope
    }

    /** Returns the current depth, including the top scope */
    int depth() {
      return top != null ? 1 + stack.size() : 0;
    }

    // DQH - regrettably needed for pre-existing tests
    void clear() {
      stack.clear();
      top = null;
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

  private void scheduleRootIterationScopeCleanup(ScopeStack scopeStack, ContinuableScope scope) {
    if (rootIterationScopes == null) {
      synchronized (this) {
        if (rootIterationScopes == null) {
          rootIterationScopes = new ConcurrentHashMap<>();
          RootIterationCleaner.scheduleFor(rootIterationScopes);
        }
      }
    }
    rootIterationScopes.put(scopeStack, scope);
  }

  private void cancelRootIterationScopeCleanup(ScopeStack scopeStack, ContinuableScope scope) {
    if (rootIterationScopes != null) {
      rootIterationScopes.remove(scopeStack, scope);
    }
  }

  /**
   * Background task to clean-up scopes from overdue root iterations that have no surrounding scope.
   */
  private static final class RootIterationCleaner
      implements AgentTaskScheduler.Task<Map<ScopeStack, ContinuableScope>> {
    private static final RootIterationCleaner CLEANER = new RootIterationCleaner();

    public static void scheduleFor(Map<ScopeStack, ContinuableScope> rootIterationScopes) {
      long period = Math.min(iterationKeepAlive, 10_000);
      AgentTaskScheduler.INSTANCE.scheduleAtFixedRate(
          CLEANER, rootIterationScopes, iterationKeepAlive, period, TimeUnit.MILLISECONDS);
    }

    @Override
    public void run(Map<ScopeStack, ContinuableScope> rootIterationScopes) {
      Iterator<Map.Entry<ScopeStack, ContinuableScope>> itr =
          rootIterationScopes.entrySet().iterator();

      long cutOff = System.currentTimeMillis() - iterationKeepAlive;

      while (itr.hasNext()) {
        Map.Entry<ScopeStack, ContinuableScope> entry = itr.next();

        ScopeStack scopeStack = entry.getKey();
        ContinuableScope rootScope = entry.getValue();

        if (!rootScope.alive()) { // no need to track this anymore
          itr.remove();
        } else if (NANOSECONDS.toMillis(rootScope.span.getStartTime()) < cutOff) {
          // mark scope as overdue to allow cleanup and avoid further spans being attached
          scopeStack.overdueRootScope = rootScope;
          rootScope.span.finishEndToEnd();
          itr.remove();
        }
      }
    }
  }
}
