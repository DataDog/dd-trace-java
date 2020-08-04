package datadog.trace.core.scopemanager;

import com.timgroup.statsd.StatsDClient;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ScopeSource;
import datadog.trace.context.ScopeListener;
import datadog.trace.context.TraceScope;
import datadog.trace.core.jfr.DDScopeEventFactory;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * The primary ScopeManager. This class has ownership of the core ThreadLocal containing the
 * currently active Scope. Such scopes can be suspended with a Continuation to prevent the trace
 * from being reported even if all related spans are finished. It also delegates to other
 * ScopeInterceptors to provide additional functionality.
 */
@Slf4j
public class ContinuableScopeManager extends ScopeInterceptor.DelegatingInterceptor
    implements AgentScopeManager {
  final ThreadLocal<ScopeStack> tlsScopeStack =
      new ThreadLocal<ScopeStack>() {
        @Override
        protected final ScopeStack initialValue() {
          return new ScopeStack();
        }
      };

  private final List<ScopeListener> scopeListeners;
  private final int depthLimit;
  private final StatsDClient statsDClient;
  private final boolean strictMode;

  public ContinuableScopeManager(
      final int depthLimit,
      final DDScopeEventFactory scopeEventFactory,
      final StatsDClient statsDClient,
      final boolean strictMode) {
    this(
        depthLimit,
        scopeEventFactory,
        statsDClient,
        strictMode,
        new CopyOnWriteArrayList<ScopeListener>());
  }

  // Separate constructor to allow passing scopeListeners to super arg and assign locally.
  private ContinuableScopeManager(
      final int depthLimit,
      final DDScopeEventFactory scopeEventFactory,
      final StatsDClient statsDClient,
      final boolean strictMode,
      final List<ScopeListener> scopeListeners) {
    super(
        new EventScopeInterceptor(
            scopeEventFactory, new ListenerScopeInterceptor(scopeListeners, null)));
    this.depthLimit = depthLimit == 0 ? Integer.MAX_VALUE : depthLimit;
    this.statsDClient = statsDClient;
    this.strictMode = strictMode;
    this.scopeListeners = scopeListeners;
  }

  @Override
  public AgentScope activate(final AgentSpan span, final ScopeSource source) {
    ScopeStack scopeStack = scopeStack();

    final ContinuableScope active = scopeStack.top();
    if (active != null && active.span().equals(span)) {
      active.incrementReferences();
      return active;
    }

    // DQH - This check could go before the check above, since depth limit checking is fast
    final int currentDepth = scopeStack.depth();
    if (depthLimit <= currentDepth) {
      log.debug("Scope depth limit exceeded ({}).  Returning NoopScope.", currentDepth);
      return AgentTracer.NoopAgentScope.INSTANCE;
    }

    return handleSpan(null, span, source);
  }

  @Override
  public Scope handleSpan(final AgentSpan span) {
    return handleSpan(null, span, ScopeSource.INSTRUMENTATION);
  }

  private Scope handleSpan(
      final Continuation continuation, final AgentSpan span, final ScopeSource source) {
    final ContinuableScope scope =
        new ContinuableScope(this, continuation, delegate.handleSpan(span), source);
    scopeStack().push(scope);
    scope.afterActivated();
    return scope;
  }

  @Override
  public TraceScope active() {
    return scopeStack().top();
  }

  @Override
  public AgentSpan activeSpan() {
    final Scope active = scopeStack().top();
    return active == null ? null : active.span();
  }

  /** Attach a listener to scope activation events */
  public void addScopeListener(final ScopeListener listener) {
    scopeListeners.add(listener);
  }

  protected ScopeStack scopeStack() {
    return this.tlsScopeStack.get();
  }

  private static final class ContinuableScope extends DelegatingScope implements Scope {
    private final ContinuableScopeManager scopeManager;

    /** Scope to placed in the thread local after close. May be null. */
    // private final ContinuableScope toRestore;

    /** Continuation that created this scope. May be null. */
    private final ContinuableScopeManager.Continuation continuation;
    /** Flag to propagate this scope across async boundaries. */
    private final AtomicBoolean isAsyncPropagating = new AtomicBoolean(false);

    private final ScopeSource source;

    private final AtomicInteger referenceCount = new AtomicInteger(1);

    ContinuableScope(
        final ContinuableScopeManager scopeManager,
        final ContinuableScopeManager.Continuation continuation,
        final Scope delegate,
        final ScopeSource source) {
      super(delegate);
      assert delegate.span() != null;
      this.scopeManager = scopeManager;
      this.continuation = continuation;
      this.source = source;
    }

    @Override
    public void close() {
      ScopeStack scopeStack = scopeManager.scopeStack();

      // DQH - Aug 2020 - Preserving our broken reference counting semantics for the
      // first round of out-of-order handling.
      // When reference counts are being used, we don't check the stack top --
      // so potentially we undercount errors
      // Also we don't report closed until the reference count == 0 which seems
      // incorrect given the OpenTracing semantics
      // Both these issues should be corrected at a later date

      boolean alive = decrementReferences();
      if (alive) return;

      boolean onTop = scopeStack.checkTop(this);
      if (!onTop) {
        if (log.isDebugEnabled()) {
          // Using noFixupTop because I don't want to have code with side effects in logging code
          log.debug("Tried to close {} scope when not on top. Ignoring!", scopeStack.noFixupTop());
        }

        scopeManager.statsDClient.incrementCounter("scope.close.error");

        if (source == ScopeSource.MANUAL) {
          scopeManager.statsDClient.incrementCounter("scope.user.close.error");

          if (scopeManager.strictMode) {
            throw new RuntimeException("Tried to close scope when not on top");
          }
        }

        return;
      }

      if (null != continuation) {
        span().context().getTrace().cancelContinuation(continuation);
      }
      scopeStack.blindPop();

      // DQH - As covered above, I feel our close notification semantics are incorrect with
      // especially where reference counting is concerned.  Unfortunately, sorting out the
      // semantics will also require sorting out the tests which have codified the ill-behavior.
      onProperClose();
    }

    /*
     * Exists to allow stack unwinding to do a delayed call to close when the close is
     * finished properly.  e.g. When the scope is back on the top of the stack.
     *
     * DQH - If we clean-up the delegation code & notification semantics at later time,
     * I would hope this becomes unnecessary.
     */
    final void onProperClose() {
      super.close();
    }

    final void incrementReferences() {
      referenceCount.incrementAndGet();
    }

    /** Decrements ref count -- returns true if the scope is still alive */
    final boolean decrementReferences() {
      return referenceCount.decrementAndGet() > 0;
    }

    /** Returns true if the scope is still alive (non-zero ref count) */
    final boolean alive() {
      return referenceCount.get() > 0;
    }

    @Override
    public boolean isAsyncPropagating() {
      return isAsyncPropagating.get();
    }

    @Override
    public void setAsyncPropagation(final boolean value) {
      isAsyncPropagating.set(value);
    }

    /**
     * The continuation returned must be closed or activated or the trace will not finish.
     *
     * @return The new continuation, or null if this scope is not async propagating.
     */
    @Override
    public ContinuableScopeManager.Continuation capture() {
      if (isAsyncPropagating()) {
        final ContinuableScopeManager.Continuation continuation =
            new ContinuableScopeManager.Continuation(scopeManager, span(), source);
        return continuation.register();
      } else {
        return null;
      }
    }

    @Override
    public String toString() {
      return super.toString() + "->" + span();
    }
  }

  static final class ScopeStack {
    ContinuableScope[] stack = new ContinuableScope[16];
    int topPos = 0;

    /**
     * top - accesses the top of the ScopeStack making sure the Scope on-top is still active If the
     * top scope isn't active, then the stack is popped back to the top-most active Scope
     */
    final ContinuableScope top() {
      int priorTopPos = this.topPos;
      if (priorTopPos == 0) {
        return null;
      }

      // localizing & clamping stackPos to enable ArrayBoundsCheck elimination
      // only bothering to do this here because of the loop below
      ContinuableScope[] stack = this.stack;
      priorTopPos = Math.min(priorTopPos, stack.length);

      // Peel first iteration
      ContinuableScope topScope = stack[priorTopPos];
      if (topScope.alive()) {
        return topScope;
      }

      // null out top position, it is no longer alive
      stack[topPos] = null;

      for (int curPos = topPos - 1; curPos > 0; --curPos) {
        ContinuableScope curScope = stack[curPos];
        if (curScope.alive()) {
          // save the position for next time
          topPos = curPos;

          if (topPos < stack.length / 4) {
            this.stack = Arrays.copyOf(stack, stack.length / 2);
          }

          return curScope;
        }

        // no longer alive -- trigger listener & null out
        curScope.onProperClose();
        stack[curPos] = null;
      }

      // empty stack -- save topPos for next time
      topPos = 0;
      return null;
    }

    /**
     * Similar to top but without the fix-up behavior that skips over any closed Scopes Mostly
     * useful in logging to avoid side effects, but could be used in other places with caution.
     */
    final ContinuableScope noFixupTop() {
      return stack[topPos];
    }

    /**
     * Pushes a new scope unto the stack Currently, the new scope is pushed onto the stack without
     * any stack fix-up. This works under two assumptions... 1 - Normally, the stack doesn't need
     * fix-up because the stack is proactively clean by Scope.close 2 - If the stack does need
     * fix-up, it has probably already been done by calling active to get the parent scope
     */
    final void push(final ContinuableScope scope) {
      // no proactive stack cleaning in push
      // In most cases, the span construction will have asked for the activeScope
      // and done any necessary stack clean-up

      ++topPos;
      if (topPos == stack.length) {
        // Could scan the stack for dead activations and compact before expansion.
        // Probably not worth it
        stack = Arrays.copyOf(stack, stack.length * 2);
      }
      stack[topPos] = scope;
    }

    /**
     * Fast check to see if the expectedScope is on top the stack -- this is done with any fix-up
     */
    final boolean checkTop(ContinuableScope expectedScope) {
      return expectedScope.equals(stack[topPos]);
    }

    /**
     * Blind pop of the top stack entry This is done without fix-up, checking the stack top, or even
     * a depth check Responsibility lies with the caller to do the diligence of calling depth or
     * checkTop ahead of calling blindPop.
     */
    final void blindPop() {
      stack[topPos--] = null;
    }

    /** Returns the current stack depth */
    final int depth() {
      return topPos;
    }

    // DQH - regrettably needed for pre-existing tests
    final void clear() {
      topPos = 0;
      Arrays.fill(stack, null);
    }
  }

  /**
   * This class must not be a nested class of ContinuableScope to avoid avoid an unconstrained chain
   * of references (using too much memory).
   */
  private static final class Continuation implements AgentScope.Continuation {
    public WeakReference<AgentScope.Continuation> ref;

    private final ContinuableScopeManager scopeManager;
    private final AgentSpan spanUnderScope;
    private final ScopeSource source;

    private final AgentTrace trace;
    private final AtomicBoolean used = new AtomicBoolean(false);

    private Continuation(
        final ContinuableScopeManager scopeManager,
        final AgentSpan spanUnderScope,
        final ScopeSource source) {
      this.scopeManager = scopeManager;
      this.spanUnderScope = spanUnderScope;
      this.source = source;
      trace = spanUnderScope.context().getTrace();
    }

    private Continuation register() {
      trace.registerContinuation(this);
      return this;
    }

    @Override
    public AgentScope activate() {
      if (used.compareAndSet(false, true)) {
        final AgentScope scope = scopeManager.handleSpan(this, spanUnderScope, source);
        log.debug("t_id={} -> activating continuation {}", spanUnderScope.getTraceId(), this);
        return scope;
      } else {
        log.debug(
            "Failed to activate continuation. Reusing a continuation not allowed. Spans may be reported separately.");
        return scopeManager.handleSpan(null, spanUnderScope, source);
      }
    }

    @Override
    public void cancel() {
      if (used.compareAndSet(false, true)) {
        trace.cancelContinuation(this);
      } else {
        log.debug("Failed to close continuation {}. Already used.", this);
      }
    }

    @Override
    public boolean isRegistered() {
      return ref != null;
    }

    @Override
    public WeakReference<AgentScope.Continuation> register(final ReferenceQueue referenceQueue) {
      ref = new WeakReference<AgentScope.Continuation>(this, referenceQueue);
      return ref;
    }

    @Override
    public void cancel(final Set<WeakReference<AgentScope.Continuation>> weakReferences) {
      weakReferences.remove(ref);
      ref.clear();
      ref = null;
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
}
