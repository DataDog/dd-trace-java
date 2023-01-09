package datadog.opentelemetry.trace;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import java.util.concurrent.TimeUnit;

public class DDSpan implements Span {
  @Override
  public <T> Span setAttribute(AttributeKey<T> key, T value) {
    return null;
  }

  @Override
  public Span addEvent(String name, Attributes attributes) {
    return null;
  }

  @Override
  public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
    return null;
  }

  @Override
  public Span setStatus(StatusCode statusCode, String description) {
    return null;
  }

  @Override
  public Span recordException(Throwable exception, Attributes additionalAttributes) {
    return null;
  }

  @Override
  public Span updateName(String name) {
    return null;
  }

  @Override
  public void end() {}

  @Override
  public void end(long timestamp, TimeUnit unit) {}

  @Override
  public SpanContext getSpanContext() {
    return null;
  }

  @Override
  public boolean isRecording() {
    return false;
  }
}
