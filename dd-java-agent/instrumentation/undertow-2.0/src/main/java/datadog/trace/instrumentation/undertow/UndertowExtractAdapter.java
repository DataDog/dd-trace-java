package datadog.trace.instrumentation.undertow;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;
import java.util.Iterator;

public abstract class UndertowExtractAdapter
    implements AgentPropagation.ContextVisitor<HttpServerExchange> {
  abstract HeaderMap getHeaders(HttpServerExchange exchange);

  @Override
  public void forEachKey(HttpServerExchange exchange, AgentPropagation.KeyClassifier classifier) {
    HeaderMap headers = getHeaders(exchange);

    Iterator<HeaderValues> iterator = headers.iterator();
    while (iterator.hasNext()) {
      HeaderValues values = iterator.next();
      if (!values.isEmpty()
          && !classifier.accept(values.getHeaderName().toString(), values.get(0))) {
        return;
      }
    }
  }

  public static class Request extends UndertowExtractAdapter {
    public static final Request GETTER = new Request();

    @Override
    HeaderMap getHeaders(HttpServerExchange exchange) {
      return exchange.getRequestHeaders();
    }
  }

  public static class Response extends UndertowExtractAdapter {
    public static final Response GETTER = new Response();

    @Override
    HeaderMap getHeaders(HttpServerExchange exchange) {
      return exchange.getResponseHeaders();
    }
  }
}
