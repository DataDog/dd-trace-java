package datadog.trace.instrumentation.undertow;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import io.undertow.server.HttpServerExchange;

public class UndertowBlockResponseFunction implements BlockResponseFunction {
  private final HttpServerExchange exchange;

  public UndertowBlockResponseFunction(HttpServerExchange exchange) {
    this.exchange = exchange;
  }

  @Override
  public boolean tryCommitBlockingResponse(int statusCode, BlockingContentType templateType) {
    Flow.Action.RequestBlockingAction rab =
        new Flow.Action.RequestBlockingAction(statusCode, templateType);
    exchange.putAttachment(UndertowBlockingHandler.REQUEST_BLOCKING_DATA, rab);
    if (exchange.isInIoThread()) {
      exchange.dispatch(UndertowBlockingHandler.INSTANCE);
    } else {
      UndertowBlockingHandler.INSTANCE.handleRequest(exchange);
    }
    return true;
  }
}
