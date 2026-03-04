package datadog.trace.instrumentation.opentracing31;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanWrapper;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.api.WithAgentSpan;
import datadog.trace.instrumentation.opentracing.LogHandler;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import java.util.Map;

class OTSpan implements Span, WithAgentSpan, SpanWrapper {
  private final AgentSpan delegate;
  private final TypeConverter converter;
  private final LogHandler logHandler;

  OTSpan(final AgentSpan delegate, final TypeConverter converter, final LogHandler logHandler) {
    this.delegate = delegate;
    this.converter = converter;
    this.logHandler = logHandler;
  }

  @Override
  public SpanContext context() {
    return converter.toSpanContext(delegate.context());
  }

  @Override
  public OTSpan setTag(final String key, final String value) {
    delegate.setTag(key, value);
    return this;
  }

  @Override
  public OTSpan setTag(final String key, final boolean value) {
    delegate.setTag(key, value);
    return this;
  }

  @Override
  public OTSpan setTag(final String key, final Number value) {
    delegate.setTag(key, value);
    return this;
  }

  @Override
  public OTSpan log(final Map<String, ?> fields) {
    logHandler.log(fields, delegate);
    return this;
  }

  @Override
  public OTSpan log(final long timestampMicroseconds, final Map<String, ?> fields) {
    logHandler.log(timestampMicroseconds, fields, delegate);
    return this;
  }

  @Override
  public OTSpan log(final String event) {
    logHandler.log(event, delegate);
    return this;
  }

  @Override
  public OTSpan log(final long timestampMicroseconds, final String event) {
    logHandler.log(timestampMicroseconds, event, delegate);
    return this;
  }

  @Override
  public OTSpan setBaggageItem(final String key, final String value) {
    delegate.setBaggageItem(key, value);
    return this;
  }

  @Override
  public String getBaggageItem(final String key) {
    return delegate.getBaggageItem(key);
  }

  @Override
  public OTSpan setOperationName(final String operationName) {
    delegate.setOperationName(UTF8BytesString.create(operationName));
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
    return delegate.hashCode();
  }

  @Override
  public AgentSpan asAgentSpan() {
    return delegate;
  }
}
