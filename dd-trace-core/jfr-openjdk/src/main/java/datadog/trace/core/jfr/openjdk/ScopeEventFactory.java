package datadog.trace.core.jfr.openjdk;

import datadog.trace.bootstrap.instrumentation.api.AgentScopeManager;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.ScopeListener;
import jdk.jfr.EventType;

/** Event factory for {@link ScopeEvent} */
public class ScopeEventFactory implements ScopeListener {
  private final ThreadLocal<ScopeEvent> scopeEventThreadLocal = new ThreadLocal<>();
  private final EventType eventType;
  private final AgentScopeManager scopeManager;

  public ScopeEventFactory(AgentScopeManager scopeManager) throws ClassNotFoundException {
    ExcludedVersions.checkVersionExclusion();
    // Note: Loading ScopeEvent when ScopeEventFactory is loaded is important because it also loads
    // JFR classes - which may not be present on some JVMs
    eventType = EventType.getEventType(ScopeEvent.class);
    this.scopeManager = scopeManager;
  }

  @Override
  public void afterScopeActivated() {
    ScopeEvent scopeEvent = scopeEventThreadLocal.get();

    if (scopeEvent != null) {
      scopeEvent.finish();
    }

    if (eventType.isEnabled()) {
      AgentSpan span = scopeManager.activeSpan();

      ScopeEvent nextScopeEvent =
          new ScopeEvent(span.context().getTraceId(), span.context().getSpanId());
      nextScopeEvent.start();
      scopeEventThreadLocal.set(nextScopeEvent);
    } else {
      scopeEventThreadLocal.remove();
    }
  }

  @Override
  public void afterScopeClosed() {
    ScopeEvent scopeEvent = scopeEventThreadLocal.get();

    if (scopeEvent != null) {
      scopeEvent.finish();
      scopeEventThreadLocal.remove();
    }
  }
}
