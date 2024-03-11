package datadog.trace.instrumentation.openlineage;

import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;

public class OLSpanBuilder implements AgentTracer.SpanBuilder {

  // Would it make sense to take a similar approach to the Otel instrumentation?
  @Override
  public AgentSpan start() {
    return null;
  }

  @Override
  public AgentTracer.SpanBuilder asChildOf(AgentSpan.Context toContext) {
    return null;
  }

  @Override
  public AgentTracer.SpanBuilder ignoreActiveSpan() {
    return null;
  }

  @Override
  public AgentTracer.SpanBuilder withTag(String key, String value) {
    return null;
  }

  @Override
  public AgentTracer.SpanBuilder withTag(String key, boolean value) {
    return null;
  }

  @Override
  public AgentTracer.SpanBuilder withTag(String key, Number value) {
    return null;
  }

  @Override
  public AgentTracer.SpanBuilder withTag(String tag, Object value) {
    return null;
  }

  @Override
  public AgentTracer.SpanBuilder withStartTimestamp(long microseconds) {
    return null;
  }

  @Override
  public AgentTracer.SpanBuilder withServiceName(String serviceName) {
    return null;
  }

  @Override
  public AgentTracer.SpanBuilder withResourceName(String resourceName) {
    return null;
  }

  @Override
  public AgentTracer.SpanBuilder withErrorFlag() {
    return null;
  }

  @Override
  public AgentTracer.SpanBuilder withSpanType(CharSequence spanType) {
    return null;
  }

  @Override
  public <T> AgentTracer.SpanBuilder withRequestContextData(RequestContextSlot slot, T data) {
    return null;
  }

  @Override
  public AgentTracer.SpanBuilder withLink(AgentSpanLink link) {
    return null;
  }
}
