package datadog.trace.instrumentation.azure.functions.worker;

import static datadog.trace.api.sampling.PrioritySampling.SAMPLER_DROP;
import static datadog.trace.api.sampling.PrioritySampling.UNSET;
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
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.IdentityHashMap;

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

  public static ContextScope startSpanScope(MiddlewareContext context, String trigger) {
    return startSpanScope(context, trigger, 0);
  }

  public static ContextScope startSpanScope(
      MiddlewareContext context, String trigger, long startTimeMicros) {
    final TraceContext traceContext = context.getTraceContext();
    AgentSpanContext.Extracted parent =
        traceContext == null
            ? null
            : extractContextAndGetSpanContext(traceContext, TraceContextExtractAdapter.GETTER);
    parent = reconcileSamplingPriority(parent, traceContext);

    final AgentSpan span =
        startTimeMicros > 0
            ? startSpan("azure-functions", AZURE_FUNCTIONS_REQUEST, parent, startTimeMicros)
            : startSpan("azure-functions", AZURE_FUNCTIONS_REQUEST, parent);
    DECORATE.afterStart(span);
    DECORATE.onInvoke(span, context.getFunctionName(), trigger);
    return activateSpan(span);
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

      final InputStream request =
          Base64.getDecoder().wrap(new Base64StringInputStream((String) parameterValue));
      final byte[] skipBuffer = new byte[512];
      boolean replay = false;
      boolean newFailure = false;
      final long[] position = {0};
      while (true) {
        final int first = request.read();
        if (first < 0) {
          break;
        }
        position[0]++;
        final long tag = readVarint(request, position, Long.MAX_VALUE, first);
        final int field = (int) (tag >>> 3);
        final int wireType = (int) (tag & 7);
        if (wireType == 2) {
          final long length = readVarint(request, position, Long.MAX_VALUE);
          final long end = checkedEnd(position[0], length);
          if (field == PAST_EVENTS_FIELD) {
            replay = true;
            skipFully(request, position, length, skipBuffer);
          } else if (field == NEW_EVENTS_FIELD) {
            if (containsFailureEvent(request, position, end, skipBuffer)) {
              newFailure = true;
            }
          } else {
            skipFully(request, position, length, skipBuffer);
          }
          if (position[0] != end) {
            throw new IllegalArgumentException("Invalid length-delimited field");
          }
        } else {
          skipValue(request, position, Long.MAX_VALUE, wireType, skipBuffer);
        }
        if (replay && newFailure) {
          return true;
        }
      }
      return !replay || newFailure;
    } catch (Throwable ignored) {
      return true;
    }
  }

  private static boolean containsFailureEvent(
      InputStream data, long[] position, long limit, byte[] skipBuffer) throws IOException {
    while (position[0] < limit) {
      final long tag = readVarint(data, position, limit);
      final int field = (int) (tag >>> 3);
      final int wireType = (int) (tag & 7);
      if (wireType == 2
          && (field == TASK_FAILED_FIELD || field == SUB_ORCHESTRATION_FAILED_FIELD)) {
        skipFully(data, position, limit - position[0], skipBuffer);
        return true;
      }
      skipValue(data, position, limit, wireType, skipBuffer);
    }
    return false;
  }

  private static long readVarint(InputStream data, long[] position, long limit) throws IOException {
    if (position[0] >= limit) {
      throw new IllegalArgumentException("Invalid protobuf varint");
    }
    final int first = data.read();
    if (first < 0) {
      throw new IllegalArgumentException("Invalid protobuf varint");
    }
    position[0]++;
    return readVarint(data, position, limit, first);
  }

  private static long readVarint(InputStream data, long[] position, long limit, int first)
      throws IOException {
    long value = 0;
    int current = first;
    for (int shift = 0; shift < 64; shift += 7) {
      value |= (long) (current & 0x7f) << shift;
      if ((current & 0x80) == 0) {
        return value;
      }
      if (position[0] >= limit) {
        break;
      }
      current = data.read();
      if (current < 0) {
        break;
      }
      position[0]++;
    }
    throw new IllegalArgumentException("Invalid protobuf varint");
  }

  private static void skipValue(
      InputStream data, long[] position, long limit, int wireType, byte[] skipBuffer)
      throws IOException {
    switch (wireType) {
      case 0:
        readVarint(data, position, limit);
        return;
      case 1:
        skipWithinLimit(data, position, limit, 8, skipBuffer, "Invalid fixed64 field");
        return;
      case 2:
        final long length = readVarint(data, position, limit);
        skipWithinLimit(
            data, position, limit, length, skipBuffer, "Invalid length-delimited field");
        return;
      case 5:
        skipWithinLimit(data, position, limit, 4, skipBuffer, "Invalid fixed32 field");
        return;
      default:
        throw new IllegalArgumentException("Unsupported protobuf wire type");
    }
  }

  private static void skipWithinLimit(
      InputStream data, long[] position, long limit, long length, byte[] skipBuffer, String error)
      throws IOException {
    if (length < 0 || length > limit - position[0]) {
      throw new IllegalArgumentException(error);
    }
    skipFully(data, position, length, skipBuffer);
  }

  private static void skipFully(InputStream data, long[] position, long length, byte[] skipBuffer)
      throws IOException {
    while (length > 0) {
      final int read = data.read(skipBuffer, 0, (int) Math.min(length, skipBuffer.length));
      if (read < 0) {
        throw new IllegalArgumentException("Truncated protobuf field");
      }
      position[0] += read;
      length -= read;
    }
  }

  private static long checkedEnd(long position, long length) {
    if (length < 0 || length > Long.MAX_VALUE - position) {
      throw new IllegalArgumentException("Invalid length-delimited field");
    }
    return position + length;
  }
}
