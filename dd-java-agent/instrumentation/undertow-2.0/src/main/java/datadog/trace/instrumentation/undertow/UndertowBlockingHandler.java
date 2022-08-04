package datadog.trace.instrumentation.undertow;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UndertowBlockingHandler implements HttpHandler {
  public static final UndertowBlockingHandler INSTANCE = new UndertowBlockingHandler();

  private static final Logger log = LoggerFactory.getLogger(UndertowBlockingHandler.class);
  private static final int STATUS_CODE = 403;
  private static final String RESPONSE_TEXT = "Access denied (request blocked)";
  private static final byte[] RESPONSE_BODY = RESPONSE_TEXT.getBytes(StandardCharsets.US_ASCII);

  private UndertowBlockingHandler() {}

  @Override
  public void handleRequest(HttpServerExchange exchange) {
    commitBlockingResponse(exchange);
  }

  private static void commitBlockingResponse(HttpServerExchange xchg) {
    if (xchg.isResponseStarted()) {
      log.warn("response already committed, we can't change it");
      return;
    }

    try {
      xchg.setStatusCode(STATUS_CODE);
      HeaderMap headers = xchg.getResponseHeaders();

      headers.remove(Headers.CONTENT_LENGTH);
      headers.remove(Headers.CONTENT_TYPE);
      headers.add(Headers.CONTENT_LENGTH, Integer.toString(RESPONSE_BODY.length));
      headers.add(Headers.CONTENT_TYPE, "text/plain");

      xchg.getResponseSender().send(ByteBuffer.wrap(RESPONSE_BODY));
    } catch (RuntimeException rte) {
      log.warn("Error sending blocking response", rte);
      throw rte;
    }
  }
}
