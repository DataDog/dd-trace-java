package datadog.trace.instrumentation.undertow;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.blocking.BlockingActionHelper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import java.nio.ByteBuffer;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UndertowBlockingHandler implements HttpHandler {
  public static final UndertowBlockingHandler INSTANCE = new UndertowBlockingHandler();

  private static final Logger log = LoggerFactory.getLogger(UndertowBlockingHandler.class);

  public static final AttachmentKey<Flow.Action.RequestBlockingAction> REQUEST_BLOCKING_DATA =
      AttachmentKey.create(Flow.Action.RequestBlockingAction.class);

  private UndertowBlockingHandler() {}

  @Override
  public void handleRequest(HttpServerExchange exchange) {
    Flow.Action.RequestBlockingAction rba = exchange.getAttachment(REQUEST_BLOCKING_DATA);
    commitBlockingResponse(exchange, rba);
  }

  private static void commitBlockingResponse(
      HttpServerExchange xchg, Flow.Action.RequestBlockingAction rba) {
    if (xchg.isResponseStarted()) {
      log.warn("response already committed, we can't change it");
      return;
    }

    try {
      xchg.setStatusCode(BlockingActionHelper.getHttpCode(rba.getStatusCode()));
      HeaderMap headers = xchg.getResponseHeaders();
      HeaderValues acceptHeaderValues = xchg.getRequestHeaders().get(Headers.ACCEPT);

      for (Map.Entry<String, String> h : rba.getExtraHeaders().entrySet()) {
        headers.remove(h.getKey());
        headers.add(HttpString.tryFromString(h.getKey()), h.getValue());
      }

      if (rba.getBlockingContentType() != BlockingContentType.NONE) {
        String acceptHeader = acceptHeaderValues != null ? acceptHeaderValues.peekLast() : null;
        BlockingActionHelper.TemplateType type =
            BlockingActionHelper.determineTemplateType(rba.getBlockingContentType(), acceptHeader);
        byte[] template = BlockingActionHelper.getTemplate(type);

        headers.remove(Headers.CONTENT_LENGTH);
        headers.remove(Headers.CONTENT_TYPE);
        headers.add(Headers.CONTENT_LENGTH, Integer.toString(template.length));
        headers.add(Headers.CONTENT_TYPE, BlockingActionHelper.getContentType(type));
        xchg.getResponseSender().send(ByteBuffer.wrap(template));
      } else {
        xchg.getResponseSender().send("");
      }
    } catch (RuntimeException rte) {
      log.warn("Error sending blocking response", rte);
      throw rte;
    }
  }
}
