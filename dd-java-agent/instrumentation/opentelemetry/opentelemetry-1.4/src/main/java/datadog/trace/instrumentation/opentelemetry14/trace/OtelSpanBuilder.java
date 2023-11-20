package datadog.trace.instrumentation.opentelemetry14.trace;

import static datadog.trace.instrumentation.opentelemetry14.trace.OtelConventions.toSpanType;
import static datadog.trace.instrumentation.opentelemetry14.trace.OtelExtractedContext.extract;
import static io.opentelemetry.api.trace.SpanKind.INTERNAL;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class OtelSpanBuilder implements SpanBuilder {
  private final AgentTracer.SpanBuilder delegate;
  private boolean spanKindSet;

  public OtelSpanBuilder(AgentTracer.SpanBuilder delegate) {
    this.delegate = delegate;
    this.spanKindSet = false;
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
    if (spanContext.isValid()) {
      this.delegate.withLink(new OtelSpanLink(spanContext));
    }
    return this;
  }

  @Override
  public SpanBuilder addLink(SpanContext spanContext, Attributes attributes) {
    if (spanContext.isValid()) {
      this.delegate.withLink(new OtelSpanLink(spanContext, attributes));
    }
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
    switch (key.getType()) {
      case STRING_ARRAY:
      case BOOLEAN_ARRAY:
      case LONG_ARRAY:
      case DOUBLE_ARRAY:
        if (value instanceof List) {
          List<?> valueList = (List<?>) value;
          if (valueList.isEmpty()) {
            // Store as object to prevent delegate to remove tag when value is empty
            this.delegate.withTag(key.getKey(), (Object) "");
          } else {
            for (int index = 0; index < valueList.size(); index++) {
              this.delegate.withTag(key.getKey() + "." + index, valueList.get(index));
            }
          }
        }
        break;
      default:
        this.delegate.withTag(key.getKey(), value);
        break;
    }
    return this;
  }

  @Override
  public SpanBuilder setSpanKind(SpanKind spanKind) {
    if (spanKind != null) {
      this.delegate.withSpanType(toSpanType(spanKind));
      this.spanKindSet = true;
    }
    return this;
  }

  @Override
  public SpanBuilder setStartTimestamp(long startTimestamp, TimeUnit unit) {
    this.delegate.withStartTimestamp(unit.toMicros(startTimestamp));
    return this;
  }

  @Override
  public Span startSpan() {
    // Ensure the span kind is set
    if (!this.spanKindSet) {
      setSpanKind(INTERNAL);
    }
    AgentSpan delegate = this.delegate.start();
    return new OtelSpan(delegate);
  }
}
