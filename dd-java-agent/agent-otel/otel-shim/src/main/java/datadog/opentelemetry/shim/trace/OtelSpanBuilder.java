package datadog.opentelemetry.shim.trace;

import static datadog.opentelemetry.shim.trace.OtelConventions.ANALYTICS_EVENT_SPECIFIC_ATTRIBUTES;
import static datadog.opentelemetry.shim.trace.OtelConventions.HTTP_RESPONSE_STATUS_CODE_ATTRIBUTE;
import static datadog.opentelemetry.shim.trace.OtelConventions.OPERATION_NAME_SPECIFIC_ATTRIBUTE;
import static datadog.opentelemetry.shim.trace.OtelConventions.attributeTag;
import static datadog.opentelemetry.shim.trace.OtelConventions.toSpanKindTagValue;
import static datadog.opentelemetry.shim.trace.OtelExtractedContext.extract;
import static datadog.trace.api.DDTags.ANALYTICS_SAMPLE_RATE;
import static datadog.trace.api.KnownTagCodec.DIRECTION_UNKNOWN;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static io.opentelemetry.api.trace.SpanKind.INTERNAL;
import static java.lang.Boolean.parseBoolean;
import static java.util.Locale.ROOT;

import datadog.trace.api.KnownTagCodec;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class OtelSpanBuilder implements SpanBuilder {
  private final AgentTracer.SpanBuilder delegate;
  private boolean spanKindSet;

  /** The direction of the span's kind once set; it decides direction-scoped attribute names. */
  private int direction = DIRECTION_UNKNOWN;

  /**
   * Direction-scoped attributes ({@code server.address}) set before the span kind, as name/value
   * pairs, stored once the kind is known ({@code null} if none).
   */
  private List<Object> heldUntilKind;

  private boolean ignoreActiveSpan;

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

  /**
   * HTTP status code overridden value by {@link
   * OtelConventions#HTTP_RESPONSE_STATUS_CODE_ATTRIBUTE} reserved attribute ({@code -1} if not
   * set).
   */
  private int overriddenHttpStatusCode;

  public OtelSpanBuilder(AgentTracer.SpanBuilder delegate) {
    this.delegate = delegate;
    this.spanKindSet = false;
    this.overriddenOperationName = null;
    this.overriddenAnalyticsSampleRate = -1;
    this.overriddenHttpStatusCode = -1;
  }

  @Override
  public SpanBuilder setParent(Context context) {
    AgentSpanContext extractedContext = extract(context);
    if (extractedContext != null) {
      this.delegate.asChildOf(extractedContext);
      this.ignoreActiveSpan = true;
    }
    return this;
  }

  @Override
  public SpanBuilder setNoParent() {
    this.delegate.ignoreActiveSpan().asChildOf(null);
    this.ignoreActiveSpan = true;
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
    withAttribute(key, (Object) value);
    return this;
  }

  @Override
  public SpanBuilder setAttribute(String key, long value) {
    // Check reserved attributes
    if (HTTP_RESPONSE_STATUS_CODE_ATTRIBUTE.equals(key)) {
      this.overriddenHttpStatusCode = (int) value;
      return this;
    }
    String tag = tagOf(key);
    if (tag == null) {
      holdUntilKind(key, value);
    } else {
      this.delegate.withTag(tag, value);
    }
    return this;
  }

  @Override
  public SpanBuilder setAttribute(String key, double value) {
    String tag = tagOf(key);
    if (tag == null) {
      holdUntilKind(key, value);
    } else {
      this.delegate.withTag(tag, value);
    }
    return this;
  }

  @Override
  public SpanBuilder setAttribute(String key, boolean value) {
    // Check reserved attributes
    if (ANALYTICS_EVENT_SPECIFIC_ATTRIBUTES.equals(key)) {
      this.overriddenAnalyticsSampleRate = value ? 1 : 0;
      return this;
    }
    String tag = tagOf(key);
    if (tag == null) {
      holdUntilKind(key, value);
    } else {
      this.delegate.withTag(tag, value);
    }
    return this;
  }

  @Override
  public <T> SpanBuilder setAttribute(AttributeKey<T> key, T value) {
    String name = key.getKey();
    switch (key.getType()) {
      case STRING:
        if (value instanceof String) {
          setAttribute(name, (String) value);
          break;
        }
      case BOOLEAN:
        if (value instanceof Boolean) {
          setAttribute(name, ((Boolean) value).booleanValue());
          break;
        }
      case LONG:
        if (value instanceof Number) {
          setAttribute(name, ((Number) value).longValue());
          break;
        }
      case DOUBLE:
        if (value instanceof Number) {
          setAttribute(name, ((Number) value).doubleValue());
          break;
        }
      case STRING_ARRAY:
      case BOOLEAN_ARRAY:
      case LONG_ARRAY:
      case DOUBLE_ARRAY:
        if (value instanceof List) {
          List<?> valueList = (List<?>) value;
          if (valueList.isEmpty()) {
            // Store as object to prevent delegate to remove tag when value is empty
            this.delegate.withTag(name, (Object) "");
          } else {
            for (int index = 0; index < valueList.size(); index++) {
              this.delegate.withTag(name + "." + index, valueList.get(index));
            }
          }
        }
        break;
      default:
        withAttribute(name, value);
        break;
    }
    return this;
  }

  private void withAttribute(String key, Object value) {
    String tag = tagOf(key);
    if (tag == null) {
      holdUntilKind(key, value);
    } else {
      this.delegate.withTag(tag, value);
    }
  }

  /**
   * The tag to store attribute {@code key} under, or {@code null} when it depends on the span's
   * direction and the kind is not set yet.
   */
  private String tagOf(String key) {
    if (this.direction == DIRECTION_UNKNOWN && KnownTagCodec.isDirectionScoped(key)) {
      return null;
    }
    return attributeTag(key, this.direction);
  }

  private void holdUntilKind(String key, Object value) {
    if (this.heldUntilKind == null) {
      this.heldUntilKind = new ArrayList<>(2);
    }
    this.heldUntilKind.add(key);
    this.heldUntilKind.add(value);
  }

  @Override
  public SpanBuilder setSpanKind(SpanKind spanKind) {
    if (spanKind != null) {
      String kind = toSpanKindTagValue(spanKind);
      this.delegate.withTag(SPAN_KIND, kind);
      this.spanKindSet = true;
      this.direction = KnownTagCodec.directionOfKind(kind);
      resolveHeldAttributes();
    }
    return this;
  }

  /**
   * Writes the attributes held until the kind was known. Done as soon as the kind is set, so a
   * later write to the same attribute lands after them and wins, and again at start for anything
   * still held.
   */
  private void resolveHeldAttributes() {
    if (this.heldUntilKind != null) {
      for (int i = 0; i < this.heldUntilKind.size(); i += 2) {
        String key = (String) this.heldUntilKind.get(i);
        this.delegate.withTag(attributeTag(key, this.direction), this.heldUntilKind.get(i + 1));
      }
      this.heldUntilKind = null;
    }
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
    resolveHeldAttributes();
    if (!this.ignoreActiveSpan) {
      setParent(Context.current());
    }
    AgentSpan delegate = this.delegate.start();
    // Apply overrides
    if (this.overriddenOperationName != null) {
      delegate.setOperationName(this.overriddenOperationName);
    }
    if (this.overriddenAnalyticsSampleRate != -1) {
      delegate.setMetric(ANALYTICS_SAMPLE_RATE, this.overriddenAnalyticsSampleRate);
    }
    if (this.overriddenHttpStatusCode != -1) {
      delegate.setHttpStatusCode(this.overriddenHttpStatusCode);
    }
    return new OtelSpan(delegate);
  }
}
