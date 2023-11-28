package datadog.trace.instrumentation.opentelemetry14.trace;

import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.instrumentation.opentelemetry14.trace.OtelConventions.ANALYTICS_EVENT_SPECIFIC_ATTRIBUTES;
import static datadog.trace.instrumentation.opentelemetry14.trace.OtelConventions.OPERATION_NAME_SPECIFIC_ATTRIBUTE;
import static datadog.trace.instrumentation.opentelemetry14.trace.OtelConventions.toSpanKindTagValue;
import static datadog.trace.instrumentation.opentelemetry14.trace.OtelExtractedContext.extract;
import static io.opentelemetry.api.trace.SpanKind.INTERNAL;
import static java.lang.Boolean.parseBoolean;
import static java.util.Locale.ROOT;

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
  /**
   * Operation name overridden value by {@link OtelConventions#OPERATION_NAME_SPECIFIC_ATTRIBUTE}
   * reserved attribute ({@code null} if not set).
   */
  private String overriddenOperationName;
  /**
   * Analytics sample rate metric value from {@link
   * OtelConventions#ANALYTICS_EVENT_SPECIFIC_ATTRIBUTES} reserved attribute ({@code -1} if not
   * set).
   */
  private int overriddenAnalyticsSampleRate;

  public OtelSpanBuilder(AgentTracer.SpanBuilder delegate) {
    this.delegate = delegate;
    this.spanKindSet = false;
    this.overriddenOperationName = null;
    this.overriddenAnalyticsSampleRate = -1;
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
    // Check reserved attributes
    if (OPERATION_NAME_SPECIFIC_ATTRIBUTE.equals(key) && value != null) {
      this.overriddenOperationName = value.toLowerCase(ROOT);
      return this;
    } else if (ANALYTICS_EVENT_SPECIFIC_ATTRIBUTES.equals(key) && value != null) {
      this.overriddenAnalyticsSampleRate = parseBoolean(value) ? 1 : 0;
      return this;
    }
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
    // Check reserved attributes
    if (ANALYTICS_EVENT_SPECIFIC_ATTRIBUTES.equals(key)) {
      this.overriddenAnalyticsSampleRate = value ? 1 : 0;
      return this;
    }
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
      this.delegate.withTag(SPAN_KIND, toSpanKindTagValue(spanKind));
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
    // Apply overrides
    if (this.overriddenOperationName != null) {
      delegate.setOperationName(this.overriddenOperationName);
    }
    if (this.overriddenAnalyticsSampleRate != -1) {
      delegate.setMetric(ANALYTICS_SAMPLE_RATE, this.overriddenAnalyticsSampleRate);
    }
    return new OtelSpan(delegate);
  }
}
