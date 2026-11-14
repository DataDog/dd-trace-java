package datadog.trace.instrumentation.openai_java;

import static datadog.trace.instrumentation.openai_java.OpenAiDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.function.BiConsumer;

/** Finishes failed responses; successful responses retain their existing parse/close lifecycle. */
public final class ErrorFinishingCallback<T> implements BiConsumer<T, Throwable> {
  private final AgentSpan span;

  public ErrorFinishingCallback(AgentSpan span) {
    this.span = span;
  }

  @Override
  public void accept(T response, Throwable error) {
    if (error != null) {
      DECORATE.finishSpan(span, error);
    }
  }
}
