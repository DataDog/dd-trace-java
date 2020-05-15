package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;
import datadog.trace.context.ScopeListener;
import datadog.trace.core.jfr.DDScopeEvent;
import datadog.trace.core.jfr.DDScopeEventFactory;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ContinuableScope implements DDScope {
  /** ScopeManager holding the thread-local to this scope. */
  private final ContextualScopeManager scopeManager;
  /**
   * Span contained by this scope. Async scopes will hold a reference to the parent scope's span.
   */
  private final AgentSpan spanUnderScope;

  private final DDScopeEventFactory eventFactory;
  /** Event for this scope */
  private final DDScopeEvent event;
  /** Scope to placed in the thread local after close. May be null. */
  private final DDScope toRestore;
  /** Continuation that created this scope. May be null. */
  private final Continuation continuation;
  /** Flag to propagate this scope across async boundaries. */
  private final AtomicBoolean isAsyncPropagating = new AtomicBoolean(false);
  /** depth of scope on thread */
  private final int depth;

  private final AtomicInteger referenceCount = new AtomicInteger(1);

  ContinuableScope(
      final ContextualScopeManager scopeManager,
      final AgentSpan spanUnderScope,
      final DDScopeEventFactory eventFactory) {
    this(scopeManager, null, spanUnderScope, eventFactory);
  }

  private ContinuableScope(
      final ContextualScopeManager scopeManager,
      final Continuation continuation,
      final AgentSpan spanUnderScope,
      final DDScopeEventFactory eventFactory) {
    assert spanUnderScope != null : "span must not be null";
    this.scopeManager = scopeManager;
    this.continuation = continuation;
    this.spanUnderScope = spanUnderScope;
    this.eventFactory = eventFactory;
    event = eventFactory.create(spanUnderScope.context());
    event.start();
    toRestore = scopeManager.tlsScope.get();
    scopeManager.tlsScope.set(this);
    depth = toRestore == null ? 0 : toRestore.depth() + 1;
    for (final ScopeListener listener : scopeManager.scopeListeners) {
      listener.afterScopeActivated();
    }
  }

  @Override
  public void close() {
    if (referenceCount.decrementAndGet() > 0) {
      return;
    }
    // We have to scope finish event before we finish then span (which finishes span event).
    // The reason is that we get span on construction and span event starts when span is created.
    // This means from JFR perspective scope is included into the span.
    event.finish();

    if (null != continuation) {
      spanUnderScope.context().getTrace().cancelContinuation(continuation);
    }

    for (final ScopeListener listener : scopeManager.scopeListeners) {
      listener.afterScopeClosed();
    }

    if (scopeManager.tlsScope.get() == this) {
      scopeManager.tlsScope.set(toRestore);
      if (toRestore != null) {
        for (final ScopeListener listener : scopeManager.scopeListeners) {
          listener.afterScopeActivated();
        }
      }
    } else {
      log.debug(
          "Tried to close {} scope when {} is on top. Ignoring!",
          this,
          scopeManager.tlsScope.get());
    }
  }

  @Override
  public AgentSpan span() {
    return spanUnderScope;
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
      return Continuation.create(spanUnderScope, scopeManager, eventFactory);
    } else {
      return null;
    }
  }

  @Override
  public String toString() {
    return super.toString() + "->" + spanUnderScope;
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
    private final DDScopeEventFactory eventFactory;

    private final AgentTrace trace;
    private final AtomicBoolean used = new AtomicBoolean(false);

    private Continuation(
        final AgentSpan spanUnderScope,
        final ContextualScopeManager scopeManager,
        final DDScopeEventFactory eventFactory) {

      this.spanUnderScope = spanUnderScope;
      this.scopeManager = scopeManager;
      this.eventFactory = eventFactory;
      trace = spanUnderScope.context().getTrace();
    }

    public static Continuation create(
        final AgentSpan spanUnderScope,
        final ContextualScopeManager scopeManager,
        final DDScopeEventFactory eventFactory) {
      final Continuation continuation =
          new Continuation(spanUnderScope, scopeManager, eventFactory);
      continuation.trace.registerContinuation(continuation);
      return continuation;
    }

    @Override
    public ContinuableScope activate() {
      if (used.compareAndSet(false, true)) {
        final ContinuableScope scope =
            new ContinuableScope(scopeManager, this, spanUnderScope, eventFactory);
        log.debug("Activating continuation {}, scope: {}", this, scope);
        return scope;
      } else {
        log.debug(
            "Failed to activate continuation. Reusing a continuation not allowed.  Returning a new scope. Spans may be reported separately.");
        return new ContinuableScope(scopeManager, null, spanUnderScope, eventFactory);
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
