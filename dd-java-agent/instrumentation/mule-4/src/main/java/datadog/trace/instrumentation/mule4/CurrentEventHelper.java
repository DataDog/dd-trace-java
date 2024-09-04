package datadog.trace.instrumentation.mule4;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;

import datadog.trace.api.Pair;
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
      final PrivilegedEvent event, ContextStore<EventContext, Pair> contextStore) {
    attachSpanToEventContext(event == null ? null : event.getContext(), contextStore);
  }

  public static void attachSpanToEventContext(
      final EventContext eventContext, ContextStore<EventContext, Pair> contextStore) {
    final AgentScope currentScope = currentEventScope.get();
    if (null != currentScope) {
      currentScope.close();
    }
    AgentScope newScope = null;
    if (null != eventContext) {
      Pair<AgentSpan, Object> pair = contextStore.get(eventContext);
      if (null != pair && pair.hasLeft()) {
        newScope = activateSpan(pair.getLeft());
      }
    }
    currentEventScope.set(newScope);
  }
}
