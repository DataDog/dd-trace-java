package datadog.trace.instrumentation.opentelemetry14;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static io.opentelemetry.api.trace.StatusCode.ERROR;
import static io.opentelemetry.api.trace.StatusCode.OK;
import static io.opentelemetry.api.trace.StatusCode.UNSET;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AttachableWrapper;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import java.util.concurrent.TimeUnit;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class OtelSpan implements Span {
  private final AgentSpan delegate;
  private StatusCode statusCode;
  private boolean recording;

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
    if (this.recording) {
      this.delegate.setTag(key.getKey(), value);
    }
    return this;
  }

  @Override
  public Span addEvent(String name, Attributes attributes) {
    // Not supported
    return this;
  }

  @Override
  public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
    // Not supported
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
        this.delegate.setError(false);
        this.delegate.setErrorMessage(null);
      }
    }
    return this;
  }

  @Override
  public Span recordException(Throwable exception, Attributes additionalAttributes) {
    if (this.recording) {
      // Store exception as span tags as span events are not supported yet
      this.delegate.addThrowable(exception, ErrorPriorities.UNSET);
    }
    return this;
  }

  @Override
  public Span updateName(String name) {
    if (this.recording) {
      this.delegate.setOperationName(name);
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

  AgentScope activate() {
    return activateSpan(this.delegate);
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
