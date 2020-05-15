package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.ScopeListener;
import datadog.trace.context.TraceScope;
import datadog.trace.core.jfr.DDScopeEventFactory;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ContextualScopeManager extends DelegateScopeInterceptor implements DDScopeManager {
  static final ThreadLocal<ContinuableScope> tlsScope = new ThreadLocal<>();
  private final List<ScopeListener> scopeListeners;

  private final int depthLimit;

  public ContextualScopeManager(final int depthLimit, final DDScopeEventFactory scopeEventFactory) {
    this(depthLimit, scopeEventFactory, new CopyOnWriteArrayList<ScopeListener>());
  }

  // Separate constructor to allow passing scopeListeners to super and assign locally.
  private ContextualScopeManager(
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
    final DDScope active = tlsScope.get();
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
  public AgentScope handleSpan(final AgentSpan span) {
    return handleSpan(null, span);
  }

  AgentScope handleSpan(final ContinuableScope.Continuation continuation, final AgentSpan span) {
    final ContinuableScope scope =
        new ContinuableScope(this, continuation, delegate.handleSpan(span));
    tlsScope.set(scope);
    return scope;
  }

  @Override
  public TraceScope active() {
    return tlsScope.get();
  }

  @Override
  public AgentSpan activeSpan() {
    final DDScope active = tlsScope.get();
    return active == null ? null : active.span();
  }

  /** Attach a listener to scope activation events */
  public void addScopeListener(final ScopeListener listener) {
    scopeListeners.add(listener);
  }
}
