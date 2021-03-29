package datadog.trace.core.jfr.openjdk;

import datadog.trace.api.DDId;
import datadog.trace.core.scopemanager.ExtendedScopeListener;
import java.util.ArrayDeque;
import java.util.Deque;
import jdk.jfr.EventType;

/** Event factory for {@link ScopeEvent} */
public class ScopeEventFactory implements ExtendedScopeListener {
  private final ThreadLocal<Deque<ScopeEvent>> scopeEventStack =
      ThreadLocal.withInitial(ArrayDeque::new);

  public ScopeEventFactory() {
    ExcludedVersions.checkVersionExclusion();
    // Note: Loading ScopeEvent when ScopeEventFactory is loaded is important because it also loads
    // JFR classes - which may not be present on some JVMs
    EventType.getEventType(ScopeEvent.class);
  }

  @Override
  public void afterScopeActivated(DDId traceId, DDId spanId) {
    Deque<ScopeEvent> stack = scopeEventStack.get();

    ScopeEvent scopeEvent = stack.peek();

    if (scopeEvent == null) {
      // Empty stack
      stack.push(new ScopeEvent(traceId, spanId));
    } else if (scopeEvent.getTraceId() == traceId.toLong()
        && scopeEvent.getSpanId() == spanId.toLong()) {

      // Reactivation
      scopeEvent.resume();
    } else {
      // Top being pushed down
      scopeEvent.pause();
      stack.push(new ScopeEvent(traceId, spanId));
    }
  }

  @Override
  public void afterScopeClosed() {
    Deque<ScopeEvent> stack = scopeEventStack.get();

    ScopeEvent scopeEvent = stack.pop();
    scopeEvent.finish();
  }
}
