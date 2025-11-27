package datadog.trace.instrumentation.undertow;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.internal.TraceSegment;
import io.undertow.server.HttpServerExchange;
import java.util.Map;

public class UndertowBlockResponseFunction implements BlockResponseFunction {
  private final HttpServerExchange exchange;

  public UndertowBlockResponseFunction(HttpServerExchange exchange) {
    this.exchange = exchange;
  }

  @Override
  public boolean tryCommitBlockingResponse(
      TraceSegment segment,
      int statusCode,
      BlockingContentType templateType,
      Map<String, String> extraHeaders,
      String securityResponseId) {
    Flow.Action.RequestBlockingAction rab =
        new Flow.Action.RequestBlockingAction(
            statusCode, templateType, extraHeaders, securityResponseId);
    exchange.putAttachment(UndertowBlockingHandler.TRACE_SEGMENT, segment);
    exchange.putAttachment(UndertowBlockingHandler.REQUEST_BLOCKING_DATA, rab);
    if (exchange.isInIoThread()) {
      exchange.dispatch(UndertowBlockingHandler.INSTANCE);
    } else {
      UndertowBlockingHandler.INSTANCE.handleRequest(exchange);
    }
    return true;
  }
}
