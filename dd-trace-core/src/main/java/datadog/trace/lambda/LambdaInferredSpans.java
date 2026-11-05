package datadog.trace.lambda;

import datadog.trace.api.Config;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import java.util.Map;

/** Owns at most two upstream spans derived from the Lambda event. */
final class LambdaInferredSpans {
  final AgentSpan outer;
  final AgentSpan inner;
  final AgentSpanContext parent;
  private final LambdaTrigger trigger;
  private final long started;

  LambdaInferredSpans(
      CoreTracer tracer,
      Config config,
      LambdaTrigger trigger,
      AgentSpanContext remote,
      long started) {
    this.trigger = trigger;
    this.started = started;
    AgentSpan outer = null;
    AgentSpan inner = null;
    try {
      if (trigger.wrapped != null && trigger.wrapped.name != null) {
        outer =
            create(
                tracer,
                config,
                trigger.wrapped,
                remote,
                start(trigger.wrapped, start(trigger, started)));
        remote = outer.spanContext();
      }
      if (trigger.name != null) {
        inner = create(tracer, config, trigger, remote, start(trigger, started));
        remote = inner.spanContext();
      }
    } catch (RuntimeException | Error error) {
      if (inner != null) {
        inner.finish();
      }
      if (outer != null) {
        outer.finish();
      }
      throw error;
    }
    this.outer = outer;
    this.inner = inner;
    this.parent = remote;
  }

  private static long start(LambdaTrigger trigger, long fallback) {
    return trigger.startMicros > 0 ? Math.min(trigger.startMicros, fallback) : fallback;
  }

  private static AgentSpan create(
      CoreTracer tracer,
      Config config,
      LambdaTrigger trigger,
      AgentSpanContext parent,
      long start) {
    String service = trigger.service;
    if (!trigger.serviceMapped) {
      if (config.isRemoveIntegrationServiceNamesEnabled()) {
        service = config.getServiceName();
      } else if (!config.isAwsServiceRepresentationEnabled()
          && !trigger.meta.containsKey("http.url")) {
        service = trigger.name.substring("aws.".length());
      }
    }
    AgentTracer.SpanBuilder builder =
        tracer
            .buildSpan("aws-lambda", trigger.name)
            .ignoreActiveSpan()
            .asChildOf(parent)
            .withStartTimestamp(start)
            .withServiceName(service)
            .withResourceName(trigger.resource)
            .withSpanType(trigger.type)
            .withTag(Tags.SPAN_KIND, Tags.SPAN_KIND_SERVER);
    trigger.meta.forEach(builder::withTag);
    AgentSpan span = builder.start();
    // These upstream services use the Lambda settings above, including explicit mappings.
    span.setServiceName(service);
    span.setResourceName(trigger.resource, ResourceNamePriorities.TAG_INTERCEPTOR);
    return span;
  }

  void enrich(AgentSpan invocation) {
    enrich(inner, invocation, invocation);
    enrich(outer, inner == null ? invocation : inner, invocation);
  }

  private static void enrich(AgentSpan parent, AgentSpan child, AgentSpan invocation) {
    if (parent == null) {
      return;
    }
    parent.setError(invocation.isError());
    parent.setTag("peer.service", child.getServiceName());
    for (String key :
        new String[] {
          Tags.HTTP_STATUS,
          DDTags.ERROR_MSG,
          DDTags.ERROR_TYPE,
          DDTags.ERROR_STACK,
          "_dd.appsec.enabled",
          "_dd.appsec.json"
        }) {
      Object value = invocation.getTag(key);
      if (value != null) {
        parent.setTag(key, value);
      }
    }
    Map<String, Object> structures = ((DDSpan) invocation).getMetaStruct();
    if (structures != null && structures.containsKey("appsec")) {
      parent.setMetaStruct("appsec", structures.get("appsec"));
    }
  }

  void finish(long ended) {
    try {
      if (inner != null) {
        inner.finish(trigger.async ? started : Math.max(started, ended));
      }
    } finally {
      if (outer != null) {
        outer.finish(start(trigger, started));
      }
    }
  }
}
