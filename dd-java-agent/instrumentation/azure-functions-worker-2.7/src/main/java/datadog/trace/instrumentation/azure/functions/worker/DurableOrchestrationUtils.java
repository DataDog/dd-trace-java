package datadog.trace.instrumentation.azure.functions.worker;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsUtils.recordOrchestrationError;
import static datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsUtils.startOrchestrationSpan;

import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.HistoryEvent;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.HistoryEvent.EventTypeCase;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.OrchestrationStatus;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.OrchestratorAction;
import com.microsoft.durabletask.implementation.protobuf.OrchestratorService.TaskFailureDetails;
import datadog.context.ContextScope;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.azure.DurableOrchestrationState;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

public final class DurableOrchestrationUtils {
  private static volatile Method getActionsMethod;
  private static volatile Class<?> missingActionsClass;

  private DurableOrchestrationUtils() {}

  /**
   * Returns whether an orchestration invocation should produce a span.
   *
   * <p>The Durable SDK has already decoded the request by this point. Successful replays are
   * suppressed, while a replay containing a newly failed activity, sub-orchestration, or entity
   * operation is retained. Only new events are inspected, so each history event is examined once
   * instead of rescanning the complete history on every replay. User-created spans are not
   * suppressed, because a replay may also execute new work after its last completed await.
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
          || eventType == EventTypeCase.SUBORCHESTRATIONINSTANCEFAILED
          || "ENTITYOPERATIONFAILED".equals(eventType.name())) {
        return true;
      }
    }
    return false;
  }

  public static ContextScope startSpanScope(DurableOrchestrationState state) {
    return activateSpan(startOrchestrationSpan(state, 0));
  }

  public static TaskFailureDetails failureDetails(Object result) {
    if (result == null) {
      return null;
    }
    try {
      // TaskOrchestratorResult is package-private, although its accessor and action types are
      // public.
      final Class<?> resultClass = result.getClass();
      if (resultClass == missingActionsClass) {
        return null;
      }
      Method method = getActionsMethod;
      if (method == null || method.getDeclaringClass() != resultClass) {
        method = resultClass.getDeclaredMethod("getActions");
        method.setAccessible(true);
        getActionsMethod = method;
      }
      for (Object value : (Collection<?>) method.invoke(result)) {
        if (value instanceof OrchestratorAction) {
          final OrchestratorAction action = (OrchestratorAction) value;
          if (action.hasCompleteOrchestration()
              && action.getCompleteOrchestration().getOrchestrationStatus()
                  == OrchestrationStatus.ORCHESTRATION_STATUS_FAILED) {
            return action.getCompleteOrchestration().getFailureDetails();
          }
        }
      }
    } catch (NoSuchMethodException ignored) {
      missingActionsClass = result.getClass();
    } catch (IllegalAccessException | InvocationTargetException | RuntimeException ignored) {
      // The result is an internal SDK type; do not interfere with execution if it changes.
    }
    return null;
  }

  public static void recordSdkOrchestrationError(
      AgentSpan span,
      Throwable throwable,
      TaskFailureDetails failure,
      DurableOrchestrationState state) {
    try {
      if (failure != null) {
        onOrchestrationFailure(span, failure);
      }
    } finally {
      recordOrchestrationError(span, throwable, state);
    }
  }

  public static void onOrchestrationFailure(AgentSpan span, TaskFailureDetails details) {
    if (!span.isError()) {
      span.setError(true);
      if (!details.getErrorType().isEmpty()) {
        span.setTag(DDTags.ERROR_TYPE, details.getErrorType());
      }
      if (!details.getErrorMessage().isEmpty()) {
        span.setTag(DDTags.ERROR_MSG, details.getErrorMessage());
      }
      if (details.hasStackTrace()) {
        span.setTag(DDTags.ERROR_STACK, details.getStackTrace().getValue());
      }
    }
  }
}
