package datadog.trace.core;

import datadog.trace.api.DDId;
import datadog.trace.api.SpanCorrelation;
import java.util.Objects;

final class SpanCorrelationImpl implements SpanCorrelation {
  public interface Provider {
    SpanCorrelationImpl getSpanCorrelation();
  }

  private final DDId traceId;
  private final DDId spanId;

  public SpanCorrelationImpl(DDId traceId, DDId spanId) {
    this.traceId = traceId;
    this.spanId = spanId;
  }

  @Override
  public DDId getTraceId() {
    return traceId;
  }

  @Override
  public DDId getSpanId() {
    return spanId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SpanCorrelationImpl that = (SpanCorrelationImpl) o;
    return Objects.equals(traceId, that.traceId) && Objects.equals(spanId, that.spanId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(traceId, spanId);
  }

  @Override
  public String toString() {
    return "SpanContext{" + "traceId=" + traceId + ", spanId=" + spanId + '}';
  }
}
