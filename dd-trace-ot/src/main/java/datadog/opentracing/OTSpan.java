package datadog.opentracing;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.tag.Tag;
import java.util.Map;
import java.util.Objects;

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
  public MutableSpan getRootSpan() {
    return delegate.getLocalRootSpan();
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
    return delegate.getOperationName();
  }

  @Override
  public OTSpan setOperationName(final String operationName) {
    delegate.setOperationName(operationName);
    return this;
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
  public String getResourceName() {
    return delegate.getResourceName();
  }

  @Override
  public MutableSpan setResourceName(final String resourceName) {
    return delegate.setResourceName(resourceName);
  }

  @Override
  public Integer getSamplingPriority() {
    return delegate.getSamplingPriority();
  }

  @Override
  public MutableSpan setSamplingPriority(final int newPriority) {
    return delegate.setSamplingPriority(newPriority);
  }

  @Override
  public String getSpanType() {
    return delegate.getSpanType();
  }

  @Override
  public MutableSpan setSpanType(final String type) {
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
