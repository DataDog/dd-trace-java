package datadog.trace.instrumentation.opentelemetry;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanWrapper;
import datadog.trace.bootstrap.instrumentation.api.WithAgentSpan;
import io.opentelemetry.common.AttributeValue;
import io.opentelemetry.trace.EndSpanOptions;
import io.opentelemetry.trace.Event;
import io.opentelemetry.trace.Span;
import io.opentelemetry.trace.SpanContext;
import io.opentelemetry.trace.Status;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class OtelSpan implements Span, WithAgentSpan, SpanWrapper {
  private final AgentSpan delegate;
  private final TypeConverter converter;

  OtelSpan(final AgentSpan agentSpan, final TypeConverter typeConverter) {
    delegate = agentSpan;
    converter = typeConverter;
  }

  @Override
  public void setAttribute(final String key, final String value) {
    delegate.setTag(key, value);
  }

  @Override
  public void setAttribute(final String key, final long value) {
    delegate.setTag(key, value);
  }

  @Override
  public void setAttribute(final String key, final double value) {
    delegate.setTag(key, value);
  }

  @Override
  public void setAttribute(final String key, final boolean value) {
    delegate.setTag(key, value);
  }

  @Override
  public void setAttribute(final String key, final AttributeValue value) {
    switch (value.getType()) {
      case LONG:
        delegate.setTag(key, value.getLongValue());
        break;
      case DOUBLE:
        delegate.setTag(key, value.getDoubleValue());
        break;
      case STRING:
        delegate.setTag(key, value.getStringValue());
        break;
      case BOOLEAN:
        delegate.setTag(key, value.getBooleanValue());
        break;
      default:
        // Unsupported.... Ignoring.
    }
  }

  @Override
  public void addEvent(final String name) {}

  @Override
  public void addEvent(final String name, final long timestamp) {}

  @Override
  public void addEvent(final String name, final Map<String, AttributeValue> attributes) {}

  @Override
  public void addEvent(
      final String name, final Map<String, AttributeValue> attributes, final long timestamp) {}

  @Override
  public void addEvent(final Event event) {}

  @Override
  public void addEvent(final Event event, final long timestamp) {}

  @Override
  public void setStatus(final Status status) {
    if (!status.isOk()) {
      delegate.setError(true);
      delegate.setTag(DDTags.ERROR_MSG, status.getDescription());
    }
  }

  @Override
  public void updateName(final String name) {
    delegate.setResourceName(name);
  }

  @Override
  public void end() {
    delegate.finish();
  }

  @Override
  public void end(final EndSpanOptions endOptions) {
    if (endOptions.getEndTimestamp() > 0) {
      delegate.finish(TimeUnit.NANOSECONDS.toMicros(endOptions.getEndTimestamp()));
    } else {
      delegate.finish();
    }
  }

  @Override
  public SpanContext getContext() {
    return converter.toSpanContext(delegate.context());
  }

  @Override
  public boolean isRecording() {
    return delegate.getTraceId().toLong() != 0;
  }

  @Override
  public AgentSpan asAgentSpan() {
    return delegate;
  }
}
