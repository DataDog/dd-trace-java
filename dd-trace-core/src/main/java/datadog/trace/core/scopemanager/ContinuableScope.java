package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ContinuableScope extends DelegatingScope implements DDScope {
  /** ScopeManager holding the thread-local to this scope. */
  private final ContextualScopeManager scopeManager;

  /** Scope to placed in the thread local after close. May be null. */
  private final ContinuableScope toRestore;
  /** Continuation that created this scope. May be null. */
  private final Continuation continuation;
  /** Flag to propagate this scope across async boundaries. */
  private final AtomicBoolean isAsyncPropagating = new AtomicBoolean(false);
  /** depth of scope on thread */
  private final int depth;

  private final AtomicInteger referenceCount = new AtomicInteger(1);

  ContinuableScope(
      final ContextualScopeManager scopeManager,
      final Continuation continuation,
      final AgentScope delegate) {
    super(delegate);
    assert delegate.span() != null;
    this.scopeManager = scopeManager;
    this.continuation = continuation;
    toRestore = scopeManager.tlsScope.get();
    depth = toRestore == null ? 0 : toRestore.depth() + 1;
  }

  @Override
  public void close() {
    if (referenceCount.decrementAndGet() > 0) {
      return;
    }

    if (scopeManager.tlsScope.get() != this) {
      log.debug(
          "Tried to close {} scope when {} is on top. Ignoring!",
          this,
          scopeManager.tlsScope.get());
      return;
    }

    if (null != continuation) {
      span().context().getTrace().cancelContinuation(continuation);
    }
    scopeManager.tlsScope.set(toRestore);

    super.close();
  }

  @Override
  public int depth() {
    return depth;
  }

  @Override
  public DDScope incrementReferences() {
    referenceCount.incrementAndGet();
    return this;
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
  public Continuation capture() {
    if (isAsyncPropagating()) {
      return Continuation.create(span(), scopeManager);
    } else {
      return null;
    }
  }

  @Override
  public String toString() {
    return super.toString() + "->" + span();
  }

  /**
   * Static to avoid an unconstrained chain of references (using too much memory), but nested to
   * maintain private constructor access.
   */
  @Slf4j // This is important to prevent the log messages below from referencing the super class.
  public static class Continuation implements AgentScope.Continuation {
    public WeakReference<AgentScope.Continuation> ref;

    private final AgentSpan spanUnderScope;
    private final ContextualScopeManager scopeManager;

    private final AgentTrace trace;
    private final AtomicBoolean used = new AtomicBoolean(false);

    private Continuation(
        final AgentSpan spanUnderScope, final ContextualScopeManager scopeManager) {

      this.spanUnderScope = spanUnderScope;
      this.scopeManager = scopeManager;
      trace = spanUnderScope.context().getTrace();
    }

    public static Continuation create(
        final AgentSpan spanUnderScope, final ContextualScopeManager scopeManager) {
      final Continuation continuation = new Continuation(spanUnderScope, scopeManager);
      continuation.trace.registerContinuation(continuation);
      return continuation;
    }

    @Override
    public AgentScope activate() {
      if (used.compareAndSet(false, true)) {
        final AgentScope scope = scopeManager.handleSpan(this, spanUnderScope);
        log.debug("Activating continuation {}, scope: {}", this, scope);
        return scope;
      } else {
        log.debug(
            "Failed to activate continuation. Reusing a continuation not allowed. Spans may be reported separately.");
        return scopeManager.handleSpan(null, spanUnderScope);
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
    public void cancel(final Set<WeakReference<?>> weakReferences) {
      weakReferences.remove(ref);
      ref.clear();
      ref = null;
    }
  }
}
