package datadog.trace.core.jfr.openjdk;

import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.SpanCorrelation;
import datadog.trace.context.ScopeListener;
import java.util.ArrayDeque;
import java.util.Deque;
import jdk.jfr.EventType;

/** Event factory for {@link ScopeEvent} */
public class ScopeEventFactory implements ScopeListener {
  private final ThreadLocal<Deque<ScopeEvent>> scopeEventStack =
      ThreadLocal.withInitial(ArrayDeque::new);

  public static ScopeEventFactory instance() throws Throwable {
    // Note: Loading ScopeEvent when ScopeEventFactory is loaded is important because it also loads
    // JFR classes - which may not be present on some JVMs
    EventType.getEventType(ScopeEvent.class);

    ScopeEvent event = new ScopeEvent();
    if (event.isEnabled()) {
      return new ScopeEventFactory();
    }
    throw new RuntimeException("ScopeEvents are disabled");
  }

  private ScopeEventFactory() {
    ExcludedVersions.checkVersionExclusion();
  }

  @Override
  public void afterScopeActivated() {
    Deque<ScopeEvent> stack = scopeEventStack.get();

    ScopeEvent scopeEvent = stack.peek();

    SpanCorrelation correlation = CorrelationIdentifier.get();
    if (scopeEvent == null) {
      // Empty stack
      stack.push(new ScopeEvent(correlation));
    } else if (scopeEvent.getTraceId() != correlation.getTraceId().toLong()
        || scopeEvent.getSpanId() != correlation.getSpanId().toLong()) {

      // Top being pushed down
      stack.push(new ScopeEvent(correlation));
    }
  }

  @Override
  public void afterScopeClosed() {
    Deque<ScopeEvent> stack = scopeEventStack.get();

    ScopeEvent scopeEvent = stack.pop();
    scopeEvent.finish();

    ScopeEvent parent = stack.peek();
    if (parent != null) {
      parent.addChildCpuTime(scopeEvent.getRawCpuTime());
    }
  }
}
