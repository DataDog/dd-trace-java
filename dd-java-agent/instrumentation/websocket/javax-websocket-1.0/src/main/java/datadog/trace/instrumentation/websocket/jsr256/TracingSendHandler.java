package datadog.trace.instrumentation.websocket.jsr256;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.WebsocketDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;

public class TracingSendHandler implements SendHandler {
  private final SendHandler delegate;
  private final HandlerContext handlerContext;

  public TracingSendHandler(SendHandler delegate, HandlerContext handlerContext) {
    this.delegate = delegate;
    this.handlerContext = handlerContext;
  }

  @Override
  public void onResult(SendResult sendResult) {
    final AgentSpan wsSpan = handlerContext.getWebsocketSpan();
    try (final AgentScope ignored = activateSpan(wsSpan)) {
      delegate.onResult(sendResult);
    } finally {
      if (sendResult.getException() != null) {
        DECORATE.onError(wsSpan, sendResult.getException());
      }
      DECORATE.onFrameEnd(handlerContext);
    }
  }
}
