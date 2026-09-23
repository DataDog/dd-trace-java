package datadog.trace.instrumentation.azure.functions.worker;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;

import com.microsoft.azure.functions.TraceContext;
import com.microsoft.azure.functions.internal.spi.middleware.MiddlewareContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import java.util.Base64;

public final class DurableFunctionsUtils {
  private static final String ACTIVITY_ANNOTATION = "DurableActivityTrigger";
  private static final String ENTITY_ANNOTATION = "DurableEntityTrigger";
  private static final String ORCHESTRATION_ANNOTATION = "DurableOrchestrationTrigger";

  // OrchestratorRequest protobuf field numbers.
  private static final int PAST_EVENTS_FIELD = 3;
  private static final int NEW_EVENTS_FIELD = 4;

  // HistoryEvent protobuf field numbers.
  private static final int TASK_FAILED_FIELD = 8;
  private static final int SUB_ORCHESTRATION_FAILED_FIELD = 11;

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

  public static AgentSpanContext.Extracted reconcileSamplingPriority(
      AgentSpanContext.Extracted parent, TraceContext traceContext) {
    if (parent != null
        && parent.getSamplingPriority() == SAMPLER_DROP
        && traceContext != null
        && TraceContextExtractAdapter.datadogSamplingPriority(traceContext.getTracestate())
            == UNSET) {
      return parent.withSamplingPriority(UNSET);
    }
    return parent;
  }

  public static boolean isReplayControlFlow(Throwable throwable) {
    while (throwable != null) {
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

  /**
   * Returns whether an orchestration invocation should produce a span.
   *
   * <p>Durable orchestrators replay from the beginning whenever new history arrives. Those
   * executions are implementation details rather than new logical operations, so successful replays
   * are suppressed. A replay containing a newly failed activity or sub-orchestration is retained so
   * the failure remains visible. If the trigger payload cannot be inspected, tracing fails open.
   */
  public static boolean shouldTraceOrchestration(MiddlewareContext context) {
    try {
      final String parameterName = context.getParameterName(ORCHESTRATION_ANNOTATION);
      final Object parameterValue = context.getParameterValue(parameterName);
      if (!(parameterValue instanceof String)) {
        return true;
      }

      final byte[] request = Base64.getDecoder().decode((String) parameterValue);
      boolean replay = false;
      boolean newFailure = false;
      final int[] position = {0};
      while (position[0] < request.length) {
        final long tag = readVarint(request, position, request.length);
        final int field = (int) (tag >>> 3);
        final int wireType = (int) (tag & 7);
        if (wireType == 2) {
          final long length = readVarint(request, position, request.length);
          if (length < 0 || length > request.length - position[0]) {
            return true;
          }
          final int end = position[0] + (int) length;
          if (field == PAST_EVENTS_FIELD) {
            replay = true;
          } else if (field == NEW_EVENTS_FIELD && containsFailureEvent(request, position[0], end)) {
            newFailure = true;
          }
          position[0] = end;
        } else {
          skipValue(request, position, request.length, wireType);
        }
      }
      return !replay || newFailure;
    } catch (Throwable ignored) {
      return true;
    }
  }

  private static boolean containsFailureEvent(byte[] data, int offset, int limit) {
    final int[] position = {offset};
    while (position[0] < limit) {
      final long tag = readVarint(data, position, limit);
      final int field = (int) (tag >>> 3);
      final int wireType = (int) (tag & 7);
      if (wireType == 2
          && (field == TASK_FAILED_FIELD || field == SUB_ORCHESTRATION_FAILED_FIELD)) {
        return true;
      }
      skipValue(data, position, limit, wireType);
    }
    return false;
  }

  private static long readVarint(byte[] data, int[] position, int limit) {
    long value = 0;
    for (int shift = 0; shift < 64 && position[0] < limit; shift += 7) {
      final int current = data[position[0]++] & 0xff;
      value |= (long) (current & 0x7f) << shift;
      if ((current & 0x80) == 0) {
        return value;
      }
    }
    throw new IllegalArgumentException("Invalid protobuf varint");
  }

  private static void skipValue(byte[] data, int[] position, int limit, int wireType) {
    switch (wireType) {
      case 0:
        readVarint(data, position, limit);
        return;
      case 1:
        if (limit - position[0] < 8) {
          throw new IllegalArgumentException("Invalid fixed64 field");
        }
        position[0] += 8;
        return;
      case 2:
        final long length = readVarint(data, position, limit);
        if (length < 0 || length > limit - position[0]) {
          throw new IllegalArgumentException("Invalid length-delimited field");
        }
        position[0] += (int) length;
        return;
      case 5:
        if (limit - position[0] < 4) {
          throw new IllegalArgumentException("Invalid fixed32 field");
        }
        position[0] += 4;
        return;
      default:
        throw new IllegalArgumentException("Unsupported protobuf wire type");
    }
  }
}
