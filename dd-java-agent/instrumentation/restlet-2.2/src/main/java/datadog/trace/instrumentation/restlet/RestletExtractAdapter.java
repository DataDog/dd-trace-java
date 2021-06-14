package datadog.trace.instrumentation.restlet;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.List;
import java.util.Map;

public class RestletExtractAdapter implements AgentPropagation.ContextVisitor<HttpExchange> {
  public static final RestletExtractAdapter GETTER = new RestletExtractAdapter();

  @Override
  public void forEachKey(HttpExchange exchange, AgentPropagation.KeyClassifier classifier) {
    Headers headers = exchange.getRequestHeaders();
    for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
      List<String> values = entry.getValue();
      if (!values.isEmpty() && !classifier.accept(entry.getKey(), values.get(0))) {
        return;
      }
    }
  }
}
