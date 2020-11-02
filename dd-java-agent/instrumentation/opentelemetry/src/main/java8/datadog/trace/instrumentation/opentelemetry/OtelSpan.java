package datadog.trace.instrumentation.opentelemetry;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import java.util.concurrent.TimeUnit;

public final class OtelSpan implements Span {
  private final AgentSpan delegate;
  private final TypeConverter converter;

  OtelSpan(final AgentSpan agentSpan, final TypeConverter typeConverter) {
    delegate = agentSpan;
    converter = typeConverter;
  }

  @Override
  public Span setAttribute(final String key, final String value) {
    delegate.setTag(key, value);
    return this;
  }

  @Override
  public Span setAttribute(final String key, final long value) {
    delegate.setTag(key, value);
    return this;
  }

  @Override
  public Span setAttribute(final String key, final double value) {
    delegate.setTag(key, value);
    return this;
  }

  @Override
  public Span setAttribute(final String key, final boolean value) {
    delegate.setTag(key, value);
    return this;
  }

  @Override
  public <T> Span setAttribute(AttributeKey<T> attributeKey, T t) {
    delegate.setTag(attributeKey.getKey(), t);
    return this;
  }

  @Override
  public Span addEvent(final String name) {
    return this;
  }

  @Override
  public Span addEvent(String name, long timestamp, TimeUnit unit) {
    return this;
  }

  @Override
  public Span addEvent(String s, Attributes attributes) {
    return this;
  }

  @Override
  public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
    return this;
  }

  @Override
  public Span setStatus(StatusCode statusCode) {
    if (statusCode == StatusCode.ERROR) {
      delegate.setError(true);
    }
    return this;
  }

  @Override
  public Span setStatus(StatusCode statusCode, String s) {
    if (statusCode == StatusCode.ERROR) {
      delegate.setError(true);
      delegate.setTag(DDTags.ERROR_MSG, s);
    }
    return this;
  }

  @Override
  public Span recordException(Throwable throwable) {
    delegate.addThrowable(throwable);
    return this;
  }

  @Override
  public Span recordException(Throwable throwable, Attributes attributes) {
    attributes.forEach(this::setAttribute);
    delegate.addThrowable(throwable);
    return this;
  }

  @Override
  public Span updateName(final String name) {
    delegate.setResourceName(name);
    return this;
  }

  @Override
  public void end() {
    delegate.finish();
  }

  @Override
  public void end(long timestamp, TimeUnit unit) {
    delegate.finish(unit.toMicros(timestamp));
  }

  @Override
  public SpanContext getSpanContext() {
    return converter.toSpanContext(delegate.context());
  }

  @Override
  public boolean isRecording() {
    return delegate.getTraceId().toLong() != 0;
  }

  public AgentSpan getDelegate() {
    return delegate;
  }
}
