package datadog.trace.instrumentation.undertow;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HeaderValues;

import java.util.Iterator;

public class UndertowExtractAdapter implements AgentPropagation.ContextVisitor<HttpServerExchange> {
  public static final UndertowExtractAdapter GETTER = new UndertowExtractAdapter();

  @Override
  public void forEachKey(HttpServerExchange exchange, AgentPropagation.KeyClassifier classifier) {
    HeaderMap headers = exchange.getRequestHeaders();

    Iterator<HeaderValues> iterator = headers.iterator();
    while (iterator.hasNext()) {
      HeaderValues values = iterator.next();
      if (!values.isEmpty()
          && !classifier.accept(values.getHeaderName().toString(), values.get(0))) {
        return;
      }
    }
  }
}
