package datadog.trace.opentelemetry1;

import static io.opentelemetry.api.trace.StatusCode.ERROR;
import static io.opentelemetry.api.trace.StatusCode.OK;
import static io.opentelemetry.api.trace.StatusCode.UNSET;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import java.util.concurrent.TimeUnit;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
class OtelSpan implements Span {
  private final AgentSpan delegate;
  private StatusCode statusCode;
  private boolean recording;

  OtelSpan(AgentSpan delegate) {
    this.delegate = delegate;
    this.statusCode = UNSET;
    this.recording = true;
  }

  @Override
  public <T> Span setAttribute(AttributeKey<T> key, T value) {
    this.delegate.setTag(key.getKey(), value);
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
    if (this.statusCode == UNSET) {
      this.statusCode = statusCode;
      this.delegate.setError(statusCode == ERROR);
      this.delegate.setErrorMessage(statusCode == ERROR ? description : null);
    } else if (this.statusCode == ERROR && statusCode == OK) {
      this.delegate.setError(false);
      this.delegate.setErrorMessage(null);
    }
    return this;
  }

  @Override
  public Span recordException(Throwable exception, Attributes additionalAttributes) {
    // Not supported
    return this;
  }

  @Override
  public Span updateName(String name) {
    this.delegate.setOperationName(name);
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
    // TODO Should we use DDSpan.isFinished()?
    return this.recording;
  }
}
