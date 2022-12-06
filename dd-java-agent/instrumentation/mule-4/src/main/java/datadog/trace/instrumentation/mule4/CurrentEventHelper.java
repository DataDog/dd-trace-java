package datadog.trace.instrumentation.mule4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import org.mule.runtime.api.event.EventContext;
import org.mule.runtime.core.privileged.event.PrivilegedEvent;

public class CurrentEventHelper {
  // Keeps track of the activated scope for the currently processing event, so it can be closed
  // properly
  private static final ThreadLocal<AgentScope> currentEventScope = new ThreadLocal<>();

  public static void handleEventChange(
      final PrivilegedEvent event, ContextStore<EventContext, AgentSpan> contextStore) {
    final AgentScope currentScope = currentEventScope.get();
    if (null != currentScope) {
      currentScope.close();
    }
    AgentScope newScope = null;
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
