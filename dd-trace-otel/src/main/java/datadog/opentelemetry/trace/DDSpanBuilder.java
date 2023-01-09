package datadog.opentelemetry.trace;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import java.util.concurrent.TimeUnit;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class DDSpanBuilder implements SpanBuilder {
  private final AgentTracer.SpanBuilder delegate;

  public DDSpanBuilder(AgentTracer.SpanBuilder delegate) {
    this.delegate = delegate;
  }

  @Override
  public SpanBuilder setParent(Context context) {
    return null; // TODO Type conversion required
  }

  @Override
  public SpanBuilder setNoParent() {
    this.delegate.asChildOf(null);
    return this;
  }

  @Override
  public SpanBuilder addLink(SpanContext spanContext) {
    // Not supported
    return this;
  }

  @Override
  public SpanBuilder addLink(SpanContext spanContext, Attributes attributes) {
    // Not supported
    return this;
  }

  @Override
  public SpanBuilder setAttribute(String key, String value) {
    this.delegate.withTag(key, value);
    return this;
  }

  @Override
  public SpanBuilder setAttribute(String key, long value) {
    this.delegate.withTag(key, value);
    return this;
  }

  @Override
  public SpanBuilder setAttribute(String key, double value) {
    this.delegate.withTag(key, value);
    return this;
  }

  @Override
  public SpanBuilder setAttribute(String key, boolean value) {
    this.delegate.withTag(key, value);
    return this;
  }

  @Override
  public <T> SpanBuilder setAttribute(AttributeKey<T> key, T value) {
    this.delegate.withTag(key.getKey(), value);
    return this;
  }

  @Override
  public SpanBuilder setSpanKind(SpanKind spanKind) {
    this.delegate.withSpanType(spanKind.toString());
    return this;
  }

  @Override
  public SpanBuilder setStartTimestamp(long startTimestamp, TimeUnit unit) {
    this.delegate.withStartTimestamp(unit.toMicros(startTimestamp));
    return this;
  }

  @Override
  public Span startSpan() {
    AgentSpan delegate = this.delegate.start();
    return new DDSpan(delegate);
  }
}
