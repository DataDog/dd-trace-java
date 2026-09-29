package datadog.trace.instrumentation.azure.functions.worker;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsDecorator.AZURE_FUNCTIONS_REQUEST;
import static datadog.trace.instrumentation.azure.functions.worker.DurableFunctionsDecorator.DECORATE;

import com.microsoft.azure.functions.TraceContext;
import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareContext;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import java.util.IdentityHashMap;

public final class DurableFunctionsUtils {
  private static final String ACTIVITY_ANNOTATION = "DurableActivityTrigger";
  private static final String ENTITY_ANNOTATION = "DurableEntityTrigger";
  private static final String ORCHESTRATION_ANNOTATION = "DurableOrchestrationTrigger";

  private DurableFunctionsUtils() {}

  public static String getTrigger(MiddlewareContext context) {
    if (context.getParameterName(ORCHESTRATION_ANNOTATION) != null) {
      return "DurableOrchestration";
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
    return startSpanScope(context, trigger, 0);
  }

  public static ContextScope startSpanScope(
      MiddlewareContext context, String trigger, long startTimeMicros) {
    final TraceContext traceContext = context.getTraceContext();
    final AgentSpanContext.Extracted parent =
        traceContext == null
            ? null
            : extractContextAndGetSpanContext(traceContext, TraceContextExtractAdapter.GETTER);

    final AgentSpan span =
        startTimeMicros > 0
            ? startSpan("azure-functions", AZURE_FUNCTIONS_REQUEST, parent, startTimeMicros)
            : startSpan("azure-functions", AZURE_FUNCTIONS_REQUEST, parent);
    DECORATE.afterStart(span);
    DECORATE.onInvoke(span, context.getFunctionName(), trigger);
    return activateSpan(span);
  }

  public static ContextScope activateTraceContext(MiddlewareContext context) {
    if (context.getParameterName(ORCHESTRATION_ANNOTATION) == null) {
      return null;
    }
    final TraceContext traceContext = context.getTraceContext();
    if (traceContext == null) {
      return null;
    }

    final AgentSpanContext.Extracted extracted =
        extractContextAndGetSpanContext(traceContext, TraceContextExtractAdapter.GETTER);
    final AgentSpan remoteSpan = AgentSpan.fromSpanContext(extracted);
    return remoteSpan.isValid() ? activateSpan(remoteSpan) : null;
  }

  public static AgentSpan onOrchestrationInvoke(MiddlewareContext context) {
    final AgentSpan span = AgentSpan.current();
    if (span != null && "DurableOrchestration".equals(span.getTag("aas.function.trigger"))) {
      DECORATE.onInvoke(span, context.getFunctionName(), "DurableOrchestration");
      return span;
    }
    return null;
  }

  public static boolean isReplayControlFlow(Throwable throwable) {
    if (throwable == null) {
      return false;
    }

    final IdentityHashMap<Throwable, Boolean> seen = new IdentityHashMap<>();
    while (throwable != null && seen.put(throwable, Boolean.TRUE) == null) {
      final String className = throwable.getClass().getName();
      if ("com.microsoft.durabletask.OrchestratorBlockedException".equals(className)
          || "com.microsoft.durabletask.interruption.OrchestratorBlockedException".equals(className)
          || "com.microsoft.durabletask.interruption.ContinueAsNewInterruption".equals(className)) {
        return true;
      }
      throwable = throwable.getCause();
    }
    return false;
  }
}
