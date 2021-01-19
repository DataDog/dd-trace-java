package datadog.trace.core.jfr.openjdk;

import datadog.trace.api.DDId;
import datadog.trace.context.ScopeListener;
import jdk.jfr.EventType;

/** Event factory for {@link ScopeEvent} */
public class ScopeEventFactory implements ScopeListener {
  private final ThreadLocal<ScopeEvent> scopeEventThreadLocal = new ThreadLocal<>();
  private final EventType eventType;

  public ScopeEventFactory() throws ClassNotFoundException {
    ExcludedVersions.checkVersionExclusion();
    // Note: Loading ScopeEvent when ScopeEventFactory is loaded is important because it also loads
    // JFR classes - which may not be present on some JVMs
    eventType = EventType.getEventType(ScopeEvent.class);
  }

  @Override
  public void afterScopeActivated(DDId traceId, DDId spanId) {
    ScopeEvent scopeEvent = scopeEventThreadLocal.get();

    if (scopeEvent != null) {
      scopeEvent.finish();
    }

    if (eventType.isEnabled()) {
      ScopeEvent nextScopeEvent = new ScopeEvent(traceId, spanId);
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
