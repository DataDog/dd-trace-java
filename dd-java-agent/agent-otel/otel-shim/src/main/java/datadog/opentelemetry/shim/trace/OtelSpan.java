package datadog.opentelemetry.shim.trace;

import static datadog.opentelemetry.shim.trace.OtelConventions.applyNamingConvention;
import static datadog.opentelemetry.shim.trace.OtelConventions.applyReservedAttribute;
import static datadog.opentelemetry.shim.trace.OtelConventions.setEventsAsTag;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static io.opentelemetry.api.trace.StatusCode.ERROR;
import static io.opentelemetry.api.trace.StatusCode.OK;
import static io.opentelemetry.api.trace.StatusCode.UNSET;

import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AttachableWrapper;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class OtelSpan implements Span {
  private final AgentSpan delegate;
  private StatusCode statusCode;
  private boolean recording;
  private List<OtelSpanEvent> events;

  public OtelSpan(AgentSpan delegate) {
    this.delegate = delegate;
    if (delegate instanceof AttachableWrapper) {
      ((AttachableWrapper) delegate).attachWrapper(this);
    }
    this.statusCode = UNSET;
    this.recording = true;
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
        this.delegate.setError(statusCode == ERROR);
        this.delegate.setErrorMessage(statusCode == ERROR ? description : null);
      } else if (this.statusCode == ERROR && statusCode == OK) {
        this.statusCode = statusCode;
        this.delegate.setError(false);
        this.delegate.setErrorMessage(null);
      }
    }
    return this;
  }

  /**
   * Records information about the Throwable as a span event. `exception.escaped` cannot be
   * determined by this function and therefore is not automatically recorded. Record it manually
   * using additionalAttributes See: <a
   * href="https://javadoc.io/doc/io.opentelemetry/opentelemetry-api-trace/latest/io/opentelemetry/api/trace/Span.html#recordException(java.lang.Throwable)">...</a>
   *
   * @param exception the Throwable to record
   * @param additionalAttributes the additional attributes to record on this span event
   */
  @Override
  public Span recordException(Throwable exception, Attributes additionalAttributes) {
    if (this.recording) {
      if (this.events == null) {
        this.events = new ArrayList<>();
      }
      this.events.add(
          new OtelSpanEvent(
              "exception", processExceptionAttributes(exception, additionalAttributes)));
    }
    return this;
  }

  /**
   * processExceptionAttributes generates Attributes about the exception and sets Span error tags
   * using the processed information.
   *
   * <p>If additionalAttributes contains a key from defaultAttributes, the value set in
   * additionalAttributes will be used in Attributes and in error tag Else, the value is determined
   * from the exception itself.
   *
   * @param exception The Throwable from which to build default attributes
   * @param additionalAttributes Attributes provided by the user
   * @return Attributes collection that combines defaultExceptionAttributes with
   *     additionalAttributes
   */
  private Attributes processExceptionAttributes(
      Throwable exception, Attributes additionalAttributes) {
    // Create an AttributesBuilder with the additionalAttributes provided
    AttributesBuilder attrsBuilder = additionalAttributes.toBuilder();

    // Check whether additionalAttributes contains any of the reserved exception attribute keys

    String key = "exception.message";
    String attrsValue = additionalAttributes.get(AttributeKey.stringKey(key));
    if (attrsValue != null) { // additionalAttributes contains "exception.message"
      this.delegate.setTag(
          DDTags.ERROR_MSG,
          attrsValue); // use the value provided in additionalAttributes to set ERROR_MSG tag
    } else {
      String value = exception.getMessage(); // use exception to set ERROR_MSG tag
      this.delegate.setTag(DDTags.ERROR_MSG, value); // and append to the builder
      attrsBuilder.put(key, value);
    }

    key = "exception.type";
    attrsValue = additionalAttributes.get(AttributeKey.stringKey(key));
    if (attrsValue != null) {
      this.delegate.setTag(DDTags.ERROR_TYPE, attrsValue);
    } else {
      String value = exception.getClass().getName();
      this.delegate.setTag(DDTags.ERROR_TYPE, value);
      attrsBuilder.put(key, value);
    }

    key = "exception.stacktrace";
    attrsValue = additionalAttributes.get(AttributeKey.stringKey(key));
    if (attrsValue != null) {
      this.delegate.setTag(DDTags.ERROR_STACK, attrsValue);
    } else {
      String value = stringifyErrorStack(exception);
      this.delegate.setTag(DDTags.ERROR_STACK, value);
      attrsBuilder.put(key, value);
    }

    return attrsBuilder.build();
  }

  static String stringifyErrorStack(Throwable error) {
    final StringWriter errorString = new StringWriter();
    error.printStackTrace(new PrintWriter(errorString));
    return errorString.toString();
  }

  @Override
  public Span updateName(String name) {
    if (this.recording) {
      this.delegate.setResourceName(name);
    }
    return this;
  }

  @Override
  public void end() {
    this.recording = false;
    applyNamingConvention(this.delegate);
    setEventsAsTag(this.delegate, this.events);
    this.delegate.finish();
  }

  @Override
  public void end(long timestamp, TimeUnit unit) {
    this.recording = false;
    applyNamingConvention(this.delegate);
    setEventsAsTag(this.delegate, this.events);
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

  public AgentSpan.Context getAgentSpanContext() {
    return this.delegate.context();
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
