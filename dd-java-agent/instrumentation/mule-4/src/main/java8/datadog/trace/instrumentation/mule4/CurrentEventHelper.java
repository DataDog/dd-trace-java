package datadog.trace.instrumentation.mule4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.context.TraceScope;
import org.mule.runtime.api.event.EventContext;
import org.mule.runtime.core.privileged.event.PrivilegedEvent;

public class CurrentEventHelper {
  // Keeps track of the activated scope for the currently processing event, so it can be closed properly
  private static final ThreadLocal<TraceScope> currentEventScope = new ThreadLocal<>();

  public static void handleEventChange(
      final PrivilegedEvent event, ContextStore<EventContext, AgentSpan> contextStore) {
    final TraceScope currentScope = currentEventScope.get();
    if (null != currentScope) {
      currentScope.close();
    }
    TraceScope newScope = null;
    if (null != event) {
      EventContext eventContext = event.getContext();
      if (null != eventContext) {
        AgentSpan span = contextStore.get(eventContext);
        if (null != span) {
          newScope = activateSpan(span);
        }
      }
    }
    currentEventScope.set(newScope);
  }
}
