package datadog.trace.lambda;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.BAGGAGE_CONCERN;
import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.TRACING_CONCERN;
import static datadog.trace.lambda.LambdaEventData.get;
import static datadog.trace.lambda.LambdaEventData.headers;

import datadog.context.Context;
import datadog.context.propagation.Propagators;
import datadog.trace.api.Config;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.TracePropagationBehaviorExtract;
import datadog.trace.api.TracePropagationStyle;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.Baggage;
import java.util.Map;

/** Applies Bottlecap carrier precedence through the configured Java propagators. */
final class LambdaPropagation {
  final Context context;
  final AgentSpanContext parent;

  private LambdaPropagation(Context context, AgentSpanContext parent) {
    this.context = context;
    this.parent = parent;
  }

  static LambdaPropagation extract(
      Config config, LambdaTrigger trigger, Map<String, Object> event) {
    LambdaPropagation extracted = carrier(trigger.carrier);
    if (extracted.parent != null) {
      return extracted;
    }
    if (trigger.generatedContext != null
        && config.getTracePropagationBehaviorExtract() != TracePropagationBehaviorExtract.IGNORE
        && config.getTracePropagationStylesToExtract().stream()
            .anyMatch(
                style ->
                    style != TracePropagationStyle.NONE
                        && style != TracePropagationStyle.BAGGAGE)) {
      return new LambdaPropagation(extracted.context, trigger.generatedContext);
    }
    LambdaPropagation request = carrier(headers(get(event, "request", "headers")));
    if (request.parent != null) {
      return request;
    }
    LambdaPropagation topLevel = carrier(headers(event.get("headers")));
    if (topLevel.parent != null || !headers(event.get("headers")).isEmpty()) {
      return topLevel;
    }
    return Baggage.fromContext(request.context) == null ? extracted : request;
  }

  private static LambdaPropagation carrier(Map<String, String> carrier) {
    Context context =
        Propagators.forConcerns(TRACING_CONCERN, BAGGAGE_CONCERN)
            .extract(Context.root(), carrier, (values, visitor) -> values.forEach(visitor));
    AgentSpan span = AgentSpan.fromContext(context);
    AgentSpanContext parent = span == null ? null : span.spanContext();
    if (parent != null && DDTraceId.ZERO.equals(parent.getTraceId())) {
      parent = null;
    }
    return new LambdaPropagation(context, parent);
  }
}
