package datadog.trace.instrumentation.opentelemetry14;

import static datadog.trace.instrumentation.opentelemetry14.OtelExtractedContext.extract;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class OtelSpanBuilder implements SpanBuilder {
  private final AgentTracer.SpanBuilder delegate;

  public OtelSpanBuilder(AgentTracer.SpanBuilder delegate) {
    this.delegate = delegate;
  }

  @Override
  public SpanBuilder setParent(Context context) {
    AgentSpan.Context extractedContext = extract(context);
    if (extractedContext != null) {
      this.delegate.asChildOf(extractedContext);
    }
    return this;
  }

  @Override
  public SpanBuilder setNoParent() {
    this.delegate.asChildOf(null);
    this.delegate.ignoreActiveSpan();
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
    // Store as object to prevent delegate to remove tag when value is empty
    this.delegate.withTag(key, (Object) value);
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
    this.delegate.withSpanType(toSpanType(spanKind));
    return this;
  }

  private static String toSpanType(SpanKind spanKind) {
    switch (spanKind) {
      case CLIENT:
        return Tags.SPAN_KIND_CLIENT;
      case SERVER:
        return Tags.SPAN_KIND_SERVER;
      case PRODUCER:
        return Tags.SPAN_KIND_PRODUCER;
      case CONSUMER:
        return Tags.SPAN_KIND_CONSUMER;
      default:
      case INTERNAL:
        return spanKind.toString().toLowerCase(Locale.ROOT);
    }
  }

  @Override
  public SpanBuilder setStartTimestamp(long startTimestamp, TimeUnit unit) {
    this.delegate.withStartTimestamp(unit.toMicros(startTimestamp));
    return this;
  }

  @Override
  public Span startSpan() {
    AgentSpan delegate = this.delegate.start();
    return new OtelSpan(delegate);
  }
}
