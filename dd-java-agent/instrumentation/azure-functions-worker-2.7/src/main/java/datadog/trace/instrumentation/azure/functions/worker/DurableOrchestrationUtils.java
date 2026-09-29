package datadog.trace.instrumentation.azure.functions.worker;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsDecorator.AZURE_FUNCTIONS_REQUEST;
import static datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsDecorator.DECORATE;

import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.HistoryEvent;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.HistoryEvent.EventTypeCase;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.List;

public final class DurableOrchestrationUtils {
  private static final String ORCHESTRATION_TRIGGER = "DurableOrchestration";

  private DurableOrchestrationUtils() {}

  /**
   * Returns whether an orchestration invocation should produce a span.
   *
   * <p>The Durable SDK has already decoded the request by this point. Successful replays are
   * suppressed, while a replay containing a newly failed activity or sub-orchestration is retained.
   * Only new events are inspected, so each history event is examined once instead of rescanning the
   * complete history on every replay.
   */
  public static boolean shouldTrace(List<?> pastEvents, List<?> newEvents) {
    if (pastEvents == null || pastEvents.isEmpty()) {
      return true;
    }
    if (newEvents == null) {
      return true;
    }

    for (int index = 0; index < newEvents.size(); index++) {
      final Object event = newEvents.get(index);
      if (!(event instanceof HistoryEvent)) {
        return true;
      }
      final EventTypeCase eventType = ((HistoryEvent) event).getEventTypeCase();
      if (eventType == EventTypeCase.TASKFAILED
          || eventType == EventTypeCase.SUBORCHESTRATIONINSTANCEFAILED) {
        return true;
      }
    }
    return false;
  }

  public static ContextScope startSpanScope() {
    final AgentSpan span = startSpan("azure-functions", AZURE_FUNCTIONS_REQUEST);
    DECORATE.afterStart(span);
    DECORATE.onInvoke(span, null, ORCHESTRATION_TRIGGER);
    return activateSpan(span);
  }
}
