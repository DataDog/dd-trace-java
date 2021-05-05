package datadog.trace.instrumentation.hazelcast4;

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
    HazelcastDecorator.DECORATE.beforeFinish(span);
    span.finish();
  }

  public void onFailure(final Throwable t) {
    HazelcastDecorator.DECORATE.onError(span, t);
    HazelcastDecorator.DECORATE.beforeFinish(span);
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
