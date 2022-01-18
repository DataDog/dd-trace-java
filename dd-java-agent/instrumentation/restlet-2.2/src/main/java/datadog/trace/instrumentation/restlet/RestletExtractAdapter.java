package datadog.trace.instrumentation.restlet;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.List;
import java.util.Map;

public abstract class RestletExtractAdapter
    implements AgentPropagation.ContextVisitor<HttpExchange> {

  abstract Headers getHeaders(HttpExchange exchange);

  @Override
  public void forEachKey(HttpExchange exchange, AgentPropagation.KeyClassifier classifier) {
    Headers headers = getHeaders(exchange);
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      List<String> values = entry.getValue();
      if (!values.isEmpty() && !classifier.accept(entry.getKey(), values.get(0))) {
        return;
      }
    }
  }

  public static class Request extends RestletExtractAdapter {
    public static final Request GETTER = new Request();

    @Override
    Headers getHeaders(HttpExchange exchange) {
      return exchange.getRequestHeaders();
    }
  }

  public static class Response extends RestletExtractAdapter {
    public static final Response GETTER = new Response();

    @Override
    Headers getHeaders(HttpExchange exchange) {
      return exchange.getResponseHeaders();
    }
  }
}
