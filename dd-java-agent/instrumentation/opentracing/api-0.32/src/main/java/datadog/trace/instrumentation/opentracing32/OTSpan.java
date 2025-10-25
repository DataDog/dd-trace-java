package datadog.trace.instrumentation.opentracing32;

import datadog.trace.api.interceptor.MutableSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.SpanWrapper;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.api.WithAgentSpan;
import datadog.trace.instrumentation.opentracing.LogHandler;
import io.opentracing.Span;
import io.opentracing.SpanContext;
import io.opentracing.tag.Tag;
import java.util.Map;

/**
 * This class should be castable to MutableSpan since that is the way we've encouraged users to
 * interact with non-ot parts of our API.
 */
class OTSpan implements Span, MutableSpan, WithAgentSpan, SpanWrapper {
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
  public OTSpan setMetric(final CharSequence metric, final int value) {
    delegate.setMetric(metric, value);
    return this;
  }

  @Override
  public OTSpan setMetric(final CharSequence metric, final long value) {
    delegate.setMetric(metric, value);
    return this;
  }

  @Override
  public OTSpan setMetric(final CharSequence metric, final double value) {
    delegate.setMetric(metric, value);
    return this;
  }

  @Override
  public boolean isError() {
    return delegate.isError();
  }

  @Override
  public OTSpan setError(final boolean value) {
    delegate.setError(value, ErrorPriorities.MANUAL_INSTRUMENTATION);
    return this;
  }

  @Override
  public OTSpan getRootSpan() {
    return getLocalRootSpan();
  }

  @Override
  public OTSpan getLocalRootSpan() {
    return converter.toSpan(delegate.getLocalRootSpan());
  }

  @Override
  public <T> Span setTag(final Tag<T> tag, final T value) {
    delegate.setTag(tag.getKey(), value);
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
  public long getStartTime() {
    return delegate.getStartTime();
  }

  @Override
  public long getDurationNano() {
    return delegate.getDurationNano();
  }

  @Override
  public CharSequence getOperationName() {
    return delegate.getOperationName();
  }

  @Override
  public OTSpan setOperationName(final CharSequence operationName) {
    delegate.setOperationName(operationName);
    return this;
  }

  @Override
  public OTSpan setOperationName(final String operationName) {
    delegate.setOperationName(UTF8BytesString.create(operationName));
    return this;
  }

  @Override
  public String getServiceName() {
    return delegate.getServiceName();
  }

  @Override
  public OTSpan setServiceName(final String serviceName) {
    delegate.setServiceName(serviceName);
    return this;
  }

  @Override
  public CharSequence getResourceName() {
    return delegate.getResourceName();
  }

  @Override
  public OTSpan setResourceName(final CharSequence resourceName) {
    delegate.setResourceName(resourceName, ResourceNamePriorities.MANUAL_INSTRUMENTATION);
    return this;
  }

  @Override
  public Integer getSamplingPriority() {
    return delegate.getSamplingPriority();
  }

  @Override
  public OTSpan setSamplingPriority(final int newPriority) {
    delegate.setSamplingPriority(newPriority);
    return this;
  }

  @Override
  public String getSpanType() {
    return delegate.getSpanType();
  }

  @Override
  public OTSpan setSpanType(final CharSequence type) {
    delegate.setSpanType(type);
    return this;
  }

  @Override
  public Map<String, Object> getTags() {
    return delegate.getTags();
  }

  @Override
  public Object getTag(String key) {
    return delegate.getTag(key);
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

  @Override
  public void onSpanFinished() {}
}
