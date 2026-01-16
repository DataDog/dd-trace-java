package datadog.opentelemetry.shim.trace;

import static datadog.opentelemetry.shim.trace.OtelConventions.applyNamingConvention;
import static datadog.opentelemetry.shim.trace.OtelConventions.applyReservedAttribute;
import static datadog.opentelemetry.shim.trace.OtelConventions.applySpanEventExceptionAttributesAsTags;
import static datadog.opentelemetry.shim.trace.OtelConventions.setEventsAsTag;
import static datadog.opentelemetry.shim.trace.OtelSpanEvent.EXCEPTION_SPAN_EVENT_NAME;
import static datadog.opentelemetry.shim.trace.OtelSpanEvent.initializeExceptionAttributes;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static io.opentelemetry.api.trace.StatusCode.ERROR;
import static io.opentelemetry.api.trace.StatusCode.OK;
import static io.opentelemetry.api.trace.StatusCode.UNSET;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.AttachableWrapper;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import datadog.trace.bootstrap.instrumentation.api.ResourceNamePriorities;
import datadog.trace.bootstrap.instrumentation.api.SpanWrapper;
import datadog.trace.bootstrap.instrumentation.api.WithAgentSpan;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class OtelSpan implements Span, WithAgentSpan, SpanWrapper {
  private final AgentSpan delegate;
  private StatusCode statusCode;
  private boolean recording;

  /** Span events ({@code null} until an event is added). */
  private List<OtelSpanEvent> events;

  public OtelSpan(AgentSpan delegate) {
    this.delegate = delegate;
    if (delegate instanceof AttachableWrapper) {
      ((AttachableWrapper) delegate).attachWrapper(this);
    }
    this.statusCode = UNSET;
    this.recording = true;
    delegate.context().setIntegrationName("otel");
  }

  public static Span invalid() {
    return NoopSpan.INSTANCE;
  }

  @Override
  public <T> Span setAttribute(AttributeKey<T> key, T value) {
    if (this.recording && !applyReservedAttribute(this.delegate, key, value)) {
      switch (key.getType()) {
        case STRING_ARRAY:
        case BOOLEAN_ARRAY:
        case LONG_ARRAY:
        case DOUBLE_ARRAY:
          if (value instanceof List) {
            List<?> valueList = (List<?>) value;
            if (valueList.isEmpty()) {
              // Store as object to prevent delegate to remove tag when value is empty
              this.delegate.setTag(key.getKey(), (Object) "");
            } else {
              for (int index = 0; index < valueList.size(); index++) {
                this.delegate.setTag(key.getKey() + "." + index, valueList.get(index));
              }
            }
          }
          break;
        default:
          this.delegate.setTag(key.getKey(), value);
          break;
      }
    }
    return this;
  }

  @Override
  public Span addEvent(String name, Attributes attributes) {
    if (this.recording) {
      if (this.events == null) {
        this.events = new ArrayList<>();
      }
      this.events.add(new OtelSpanEvent(name, attributes));
    }
    return this;
  }

  @Override
  public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
    if (this.recording) {
      if (this.events == null) {
        this.events = new ArrayList<>();
      }
      this.events.add(new OtelSpanEvent(name, attributes, timestamp, unit));
    }
    return this;
  }

  @Override
  public Span setStatus(StatusCode statusCode, String description) {
    if (this.recording) {
      if (this.statusCode == UNSET) {
        this.statusCode = statusCode;
        this.delegate.setError(statusCode == ERROR, ErrorPriorities.MANUAL_INSTRUMENTATION);
        this.delegate.setErrorMessage(statusCode == ERROR ? description : null);
      } else if (this.statusCode == ERROR && statusCode == OK) {
        this.statusCode = statusCode;
        this.delegate.setError(false, ErrorPriorities.MANUAL_INSTRUMENTATION);
        this.delegate.setErrorMessage(null);
      }
    }
    return this;
  }

  @Override
  public Span recordException(Throwable exception, Attributes additionalAttributes) {
    if (this.recording) {
      if (this.events == null) {
        this.events = new ArrayList<>();
      }
      additionalAttributes = initializeExceptionAttributes(exception, additionalAttributes);
      applySpanEventExceptionAttributesAsTags(this.delegate, additionalAttributes);
      this.events.add(new OtelSpanEvent(EXCEPTION_SPAN_EVENT_NAME, additionalAttributes));
    }
    return this;
  }

  @Override
  public Span updateName(String name) {
    if (this.recording) {
      this.delegate.setResourceName(name, ResourceNamePriorities.MANUAL_INSTRUMENTATION);
    }
    return this;
  }

  @Override
  public void end() {
    this.recording = false;
    this.delegate.finish();
  }

  @Override
  public void end(long timestamp, TimeUnit unit) {
    this.recording = false;
    this.delegate.finish(unit.toMicros(timestamp));
  }

  @Override
  public SpanContext getSpanContext() {
    return OtelSpanContext.fromLocalSpan(this.delegate);
  }

  @Override
  public boolean isRecording() {
    return this.recording;
  }

  public AgentScope activate() {
    return activateSpan(this.delegate);
  }

  public AgentSpanContext getAgentSpanContext() {
    return this.delegate.context();
  }

  @Override
  public AgentSpan asAgentSpan() {
    return this.delegate;
  }

  @Override
  public void onSpanFinished() {
    applyNamingConvention(this.delegate);
    setEventsAsTag(this.delegate, this.events);
  }

  private static class NoopSpan implements Span {
    private static final Span INSTANCE = new NoopSpan();

    @Override
    public <T> Span setAttribute(AttributeKey<T> key, T value) {
      return this;
    }

    @Override
    public Span addEvent(String name, Attributes attributes) {
      return this;
    }

    @Override
    public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
      return this;
    }

    @Override
    public Span setStatus(StatusCode statusCode, String description) {
      return this;
    }

    @Override
    public Span recordException(Throwable exception, Attributes additionalAttributes) {
      return this;
    }

    @Override
    public Span updateName(String name) {
      return this;
    }

    @Override
    public void end() {}

    @Override
    public void end(long timestamp, TimeUnit unit) {}

    @Override
    public SpanContext getSpanContext() {
      return NoopSpanContext.INSTANCE;
    }

    @Override
    public boolean isRecording() {
      return false;
    }
  }

  private static class NoopSpanContext implements SpanContext {
    private static final SpanContext INSTANCE = new NoopSpanContext();

    @Override
    public String getTraceId() {
      return "00000000000000000000000000000000";
    }

    @Override
    public String getSpanId() {
      return "0000000000000000";
    }

    @Override
    public TraceFlags getTraceFlags() {
      return TraceFlags.getDefault();
    }

    @Override
    public TraceState getTraceState() {
      return TraceState.getDefault();
    }

    @Override
    public boolean isRemote() {
      return false;
    }
  }
}
