package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTrace;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.ScopeListener;
import datadog.trace.context.TraceScope;
import datadog.trace.core.jfr.DDScopeEventFactory;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
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
  static final ThreadLocal<ContinuableScope> tlsScope = new ThreadLocal<>();

  private final List<ScopeListener> scopeListeners;
  private final int depthLimit;

  public ContinuableScopeManager(
      final int depthLimit, final DDScopeEventFactory scopeEventFactory) {
    this(depthLimit, scopeEventFactory, new CopyOnWriteArrayList<ScopeListener>());
  }

  // Separate constructor to allow passing scopeListeners to super arg and assign locally.
  private ContinuableScopeManager(
      final int depthLimit,
      final DDScopeEventFactory scopeEventFactory,
      final List<ScopeListener> scopeListeners) {
    super(
        new EventScopeInterceptor(
            scopeEventFactory, new ListenerScopeInterceptor(scopeListeners, null)));
    this.depthLimit = depthLimit;
    this.scopeListeners = scopeListeners;
  }

  @Override
  public AgentScope activate(final AgentSpan span) {
    final ContinuableScope active = tlsScope.get();
    if (active != null && active.span().equals(span)) {
      return active.incrementReferences();
    }
    final int currentDepth = active == null ? 0 : active.depth();
    if (depthLimit <= currentDepth) {
      log.debug("Scope depth limit exceeded ({}).  Returning NoopScope.", currentDepth);
      return AgentTracer.NoopAgentScope.INSTANCE;
    }
    return handleSpan(span);
  }

  @Override
  public Scope handleSpan(final AgentSpan span) {
    return handleSpan(null, span);
  }

  private Scope handleSpan(final Continuation continuation, final AgentSpan span) {
    final ContinuableScope scope = new ContinuableScope(continuation, delegate.handleSpan(span));
    tlsScope.set(scope);
    scope.afterActivated();
    return scope;
  }

  @Override
  public TraceScope active() {
    return tlsScope.get();
  }

  @Override
  public AgentSpan activeSpan() {
    final Scope active = tlsScope.get();
    return active == null ? null : active.span();
  }

  /** Attach a listener to scope activation events */
  public void addScopeListener(final ScopeListener listener) {
    scopeListeners.add(listener);
  }

  private class ContinuableScope extends DelegatingScope implements Scope {
    /** Scope to placed in the thread local after close. May be null. */
    private final ContinuableScope toRestore;
    /** Continuation that created this scope. May be null. */
    private final ContinuableScopeManager.Continuation continuation;
    /** Flag to propagate this scope across async boundaries. */
    private final AtomicBoolean isAsyncPropagating = new AtomicBoolean(false);
    /** depth of scope on thread */
    private final int depth;

    private final AtomicInteger referenceCount = new AtomicInteger(1);

    ContinuableScope(
        final ContinuableScopeManager.Continuation continuation, final Scope delegate) {
      super(delegate);
      assert delegate.span() != null;
      this.continuation = continuation;
      toRestore = tlsScope.get();
      depth = toRestore == null ? 0 : toRestore.depth() + 1;
    }

    @Override
    public void close() {
      if (referenceCount.decrementAndGet() > 0) {
        return;
      }

      if (tlsScope.get() != this) {
        log.debug("Tried to close {} scope when {} is on top. Ignoring!", this, tlsScope.get());
        return;
      }

      if (null != continuation) {
        span().context().getTrace().cancelContinuation(continuation);
      }
      tlsScope.set(toRestore);

      super.close();
    }

    public int depth() {
      return depth;
    }

    public Scope incrementReferences() {
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
    public ContinuableScopeManager.Continuation capture() {
      if (isAsyncPropagating()) {
        final ContinuableScopeManager.Continuation continuation =
            new ContinuableScopeManager.Continuation(span());
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

  /**
   * This class must not be a nested class of ContinuableScope to avoid avoid an unconstrained chain
   * of references (using too much memory).
   */
  private class Continuation implements AgentScope.Continuation {
    public WeakReference<AgentScope.Continuation> ref;

    private final AgentSpan spanUnderScope;

    private final AgentTrace trace;
    private final AtomicBoolean used = new AtomicBoolean(false);

    private Continuation(final AgentSpan spanUnderScope) {

      this.spanUnderScope = spanUnderScope;
      trace = spanUnderScope.context().getTrace();
    }

    private Continuation register() {
      trace.registerContinuation(this);
      return this;
    }

    @Override
    public AgentScope activate() {
      if (used.compareAndSet(false, true)) {
        final AgentScope scope = handleSpan(this, spanUnderScope);
        log.debug("t_id={} -> activating continuation {}", spanUnderScope.getTraceId(), this);
        return scope;
      } else {
        log.debug(
            "Failed to activate continuation. Reusing a continuation not allowed. Spans may be reported separately.");
        return handleSpan(null, spanUnderScope);
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
