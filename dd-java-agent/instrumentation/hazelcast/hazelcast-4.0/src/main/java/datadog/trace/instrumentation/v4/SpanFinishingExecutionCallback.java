package datadog.trace.instrumentation.v4;

import static datadog.trace.instrumentation.v4.ClientInvocationDecorator.DECORATE;

import com.hazelcast.client.impl.protocol.ClientMessage;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.function.BiConsumer;

public class SpanFinishingExecutionCallback implements BiConsumer<ClientMessage, Throwable> {

  /** Span that we should finish and annotate when the future is complete. */
  private final AgentSpan span;

  public SpanFinishingExecutionCallback(final AgentSpan span) {
    this.span = span;
  }

  public void onResponse(ClientMessage response) {
    DECORATE.beforeFinish(span);
    DECORATE.onResult(span, response);
    span.finish();
  }

  public void onFailure(final Throwable t) {
    DECORATE.onError(span, t);
    DECORATE.beforeFinish(span);
    span.finish();
  }

  @Override
  public void accept(ClientMessage clientMessage, Throwable throwable) {
    if (throwable != null) {
      onFailure(throwable);
    } else {
      onResponse(clientMessage);
    }
  }
}
