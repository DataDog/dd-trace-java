package datadog.trace.instrumentation.azure.functions.worker;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsDecorator.AZURE_FUNCTIONS_REQUEST;
import static datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsDecorator.DECORATE;
import static datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsDecorator.ORCHESTRATION_TRIGGER;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

import com.microsoft.azure.functions.TraceContext;
import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareContext;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.azure.DurableOrchestrationState;
import java.time.Instant;

public final class DurableFunctionsUtils {
  private static final String ACTIVITY_ANNOTATION = "DurableActivityTrigger";
  private static final String ENTITY_ANNOTATION = "DurableEntityTrigger";
  private static final String ORCHESTRATION_ANNOTATION = "DurableOrchestrationTrigger";

  private DurableFunctionsUtils() {}

  public static String getTrigger(MiddlewareContext context) {
    if (context.getParameterName(ORCHESTRATION_ANNOTATION) != null) {
      return ORCHESTRATION_TRIGGER;
    }
    if (context.getParameterName(ACTIVITY_ANNOTATION) != null) {
      return "DurableActivity";
    }
    if (context.getParameterName(ENTITY_ANNOTATION) != null) {
      return "DurableEntity";
    }
    return null;
  }

  public static ContextScope startSpanScope(MiddlewareContext context, String trigger) {
    return activateSpan(
        startInvocationSpan(
            context.getFunctionName(), trigger, extractParent(context.getTraceContext()), 0));
  }

  public static long nowMicros() {
    final Instant now = Instant.now();
    return SECONDS.toMicros(now.getEpochSecond()) + NANOSECONDS.toMicros(now.getNano());
  }

  public static AgentSpan startOrchestrationErrorSpan(
      MiddlewareContext context, long startTimeMicros) {
    final DurableOrchestrationState state = DurableOrchestrationState.current();
    if (state != null) {
      return startOrchestrationSpan(state, startTimeMicros);
    }
    return startInvocationSpan(
        context.getFunctionName(),
        ORCHESTRATION_TRIGGER,
        extractParent(context.getTraceContext()),
        startTimeMicros);
  }

  public static AgentSpan startOrchestrationSpan(
      DurableOrchestrationState state, long startTimeMicros) {
    final AgentSpan parentSpan = state.parentSpan();
    return startInvocationSpan(
        state.functionName(),
        ORCHESTRATION_TRIGGER,
        parentSpan == null ? null : parentSpan.spanContext(),
        startTimeMicros);
  }

  public static AgentSpan startInvocationSpan(
      String functionName, String trigger, AgentSpanContext parent, long startTimeMicros) {
    final AgentSpan span =
        startTimeMicros > 0
            ? startSpan("azure-functions", AZURE_FUNCTIONS_REQUEST, parent, startTimeMicros)
            : startSpan("azure-functions", AZURE_FUNCTIONS_REQUEST, parent);
    DECORATE.afterStart(span);
    DECORATE.onInvoke(span, functionName, trigger);
    return span;
  }

  public static void recordOrchestrationError(
      AgentSpan span, Throwable throwable, DurableOrchestrationState state) {
    try {
      DECORATE.onInvocationError(span, throwable);
      DECORATE.beforeFinish(span);
    } finally {
      try {
        // This path is only used for failures, even if error decoration itself throws.
        if (!span.isError()) {
          span.setError(true);
        }
      } finally {
        try {
          span.finish();
        } finally {
          if (state != null && span.isError()) {
            state.markErrorRecorded();
          }
        }
      }
    }
  }

  private static AgentSpanContext.Extracted extractParent(TraceContext traceContext) {
    return traceContext == null
        ? null
        : extractContextAndGetSpanContext(traceContext, TraceContextExtractAdapter.GETTER);
  }

  public static ContextScope activateOrchestrationContext(MiddlewareContext context) {
    if (context.getParameterName(ORCHESTRATION_ANNOTATION) == null) {
      return null;
    }
    final AgentSpanContext.Extracted extracted = extractParent(context.getTraceContext());
    final AgentSpan remoteSpan = AgentSpan.fromSpanContext(extracted);
    return DurableOrchestrationState.activate(remoteSpan, context.getFunctionName());
  }

  public static AgentSpan onOrchestrationInvoke() {
    final DurableOrchestrationState state = DurableOrchestrationState.current();
    return state == null ? null : state.span();
  }

  public static boolean isReplayControlFlow(Throwable throwable) {
    if (throwable == null) {
      return false;
    }
    // The Durable middleware recognizes only the direct cause of the exception it catches.
    // A more deeply wrapped interruption is treated by the SDK as a real failure.
    final Throwable cause = throwable.getCause();
    return cause != null && isReplayInterruption(cause);
  }

  private static boolean isReplayInterruption(Throwable throwable) {
    final String className = throwable.getClass().getName();
    return "com.microsoft.durabletask.OrchestratorBlockedException".equals(className)
        || "com.microsoft.durabletask.interruption.OrchestratorBlockedException".equals(className)
        || "com.microsoft.durabletask.interruption.ContinueAsNewInterruption".equals(className);
  }
}
