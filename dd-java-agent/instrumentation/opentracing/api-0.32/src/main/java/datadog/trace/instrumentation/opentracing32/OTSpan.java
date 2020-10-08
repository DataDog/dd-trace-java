package datadog.trace.instrumentation.opentracing32;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.instrumentation.opentracing.LogHandler;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.tag.Tag;
import java.util.Map;

/**
 * This class should be castable to MutableSpan since that is the way we've encouraged users to
 * interact with non-ot parts of our API.
 */
class OTSpan implements Span, MutableSpan {
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
  public Boolean isError() {
    return delegate.isError();
  }

  @Override
  public MutableSpan setError(final boolean value) {
    return delegate.setError(value);
  }

  @Override
  public MutableSpan getLocalRootSpan() {
    return delegate.getLocalRootSpan();
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
  public long getStartTime() {
    return delegate.getStartTime();
  }

  @Override
  public long getDurationNano() {
    return delegate.getDurationNano();
  }

  @Override
  public String getOperationName() {
    return String.valueOf(delegate.getOperationName());
  }

  @Override
  public MutableSpan setOperationName(CharSequence operationName) {
    delegate.setOperationName(operationName);
    return this;
  }

  @Override
  public OTSpan setOperationName(final String operationName) {
    return (OTSpan) setOperationName(UTF8BytesString.create(operationName));
  }

  @Override
  public String getServiceName() {
    return delegate.getServiceName();
  }

  @Override
  public MutableSpan setServiceName(final String serviceName) {
    return delegate.setServiceName(serviceName);
  }

  @Override
  public CharSequence getResourceName() {
    return delegate.getResourceName();
  }

  @Override
  public MutableSpan setResourceName(final CharSequence resourceName) {
    return delegate.setResourceName(resourceName);
  }

  @Override
  public Integer getSamplingPriority() {
    return delegate.getSamplingPriority();
  }

  @Override
  public String getSpanType() {
    return delegate.getSpanType();
  }

  @Override
  public MutableSpan setSpanType(final CharSequence type) {
    return delegate.setSpanType(type);
  }

  @Override
  public Map<String, Object> getTags() {
    return delegate.getTags();
  }

  @Override
  public void finish() {
    delegate.finish();
  }

  @Override
  public void finish(final long finishMicros) {
    delegate.finish(finishMicros);
  }

  public AgentSpan getDelegate() {
    return delegate;
  }
}
