package datadog.trace.core.scopemanager;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.ScopeListener;
import datadog.trace.context.TraceScope;
import datadog.trace.core.DDSpan;
import datadog.trace.core.jfr.DDScopeEventFactory;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ContextualScopeManager implements DDScopeManager {
  static final ThreadLocal<DDScope> tlsScope = new ThreadLocal<>();
  final List<ScopeListener> scopeListeners = new CopyOnWriteArrayList<>();

  private final int depthLimit;
  private final DDScopeEventFactory scopeEventFactory;

  public ContextualScopeManager(final int depthLimit, final DDScopeEventFactory scopeEventFactory) {
    this.depthLimit = depthLimit;
    this.scopeEventFactory = scopeEventFactory;
  }

  @Override
  public AgentScope activate(final AgentSpan span, final boolean finishOnClose) {
    final DDScope active = tlsScope.get();
    if (active.span().equals(span)) {
      return active.incrementReferences();
    }
    final int currentDepth = active == null ? 0 : active.depth();
    if (depthLimit <= currentDepth) {
      log.debug("Scope depth limit exceeded ({}).  Returning NoopScope.", currentDepth);
      return AgentTracer.NoopAgentScope.INSTANCE;
    }

    if (span instanceof DDSpan) {
      return new ContinuableScope(this, (DDSpan) span, finishOnClose, scopeEventFactory);
    } else {
      // Noop Span
      return new SimpleScope(this, span, finishOnClose);
    }
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
