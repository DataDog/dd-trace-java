package datadog.opentracing;

import datadog.trace.core.DDSpan;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.tag.Tag;
import java.util.Map;
import java.util.Objects;

class OTSpan implements Span {
  private final DDSpan delegate;
  private final Converter converter;
  private final LogHandler logHandler;

  OTSpan(final DDSpan delegate, final Converter converter, final LogHandler logHandler) {
    this.delegate = delegate;
    this.converter = converter;
    this.logHandler = logHandler;
  }

  @Override
  public SpanContext context() {
    return converter.toSpanContext(delegate.context());
  }

  @Override
  public Span setTag(final String key, final String value) {
    delegate.setTag(key, value);
    return this;
  }

  @Override
  public Span setTag(final String key, final boolean value) {
    delegate.setTag(key, value);
    return this;
  }

  @Override
  public Span setTag(final String key, final Number value) {
    delegate.setTag(key, value);
    return this;
  }

  @Override
  public <T> Span setTag(final Tag<T> tag, final T value) {
    delegate.setTag(tag.getKey(), value);
    return this;
  }

  @Override
  public Span log(final Map<String, ?> fields) {
    logHandler.log(fields, delegate);
    return this;
  }

  @Override
  public Span log(final long timestampMicroseconds, final Map<String, ?> fields) {
    logHandler.log(timestampMicroseconds, fields, delegate);
    return this;
  }

  @Override
  public Span log(final String event) {
    logHandler.log(event, delegate);
    return this;
  }

  @Override
  public Span log(final long timestampMicroseconds, final String event) {
    logHandler.log(timestampMicroseconds, event, delegate);
    return this;
  }

  @Override
  public Span setBaggageItem(final String key, final String value) {
    delegate.setBaggageItem(key, value);
    return this;
  }

  @Override
  public String getBaggageItem(final String key) {
    return delegate.getBaggageItem(key);
  }

  @Override
  public Span setOperationName(final String operationName) {
    delegate.setOperationName(operationName);
    return this;
  }

  @Override
  public void finish() {
    delegate.finish();
  }

  @Override
  public void finish(final long finishMicros) {
    delegate.finish(finishMicros);
  }

  public DDSpan getDelegate() {
    return delegate;
  }

  @Override
  public boolean equals(final Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    final OTSpan otSpan = (OTSpan) o;
    return delegate.equals(otSpan.delegate);
  }

  @Override
  public int hashCode() {
    return Objects.hash(delegate);
  }
}
