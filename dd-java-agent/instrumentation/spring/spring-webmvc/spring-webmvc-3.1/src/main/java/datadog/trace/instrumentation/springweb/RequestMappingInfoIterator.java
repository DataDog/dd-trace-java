package datadog.trace.instrumentation.springweb;

import datadog.trace.api.telemetry.Endpoint;
import datadog.trace.api.telemetry.Endpoint.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.MediaTypeExpression;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

public class RequestMappingInfoIterator implements Iterator<Endpoint> {

  private final Map<RequestMappingInfo, HandlerMethod> mappings;
  private final Queue<Endpoint> queue = new LinkedList<>();
  private Iterator<Map.Entry<RequestMappingInfo, HandlerMethod>> iterator;
  private boolean first = true;

  public RequestMappingInfoIterator(final Map<RequestMappingInfo, HandlerMethod> mappings) {
    this.mappings = mappings;
  }

  private Iterator<Map.Entry<RequestMappingInfo, HandlerMethod>> iterator() {
    if (iterator == null) {
      iterator = mappings.entrySet().iterator();
    }
    return iterator;
  }

  @Override
  public boolean hasNext() {
    return !queue.isEmpty() || iterator().hasNext();
  }

  @Override
  public Endpoint next() {
    if (queue.isEmpty()) {
      fetchNext();
    }
    final Endpoint endpoint = queue.poll();
    if (endpoint == null) {
      throw new NoSuchElementException();
    }
    return endpoint;
  }

  private void fetchNext() {
    final Iterator<Map.Entry<RequestMappingInfo, HandlerMethod>> delegate = iterator();
    if (!delegate.hasNext()) {
      return;
    }
    final Map.Entry<RequestMappingInfo, HandlerMethod> nextEntry = delegate.next();
    final RequestMappingInfo nextInfo = nextEntry.getKey();
    final HandlerMethod nextHandler = nextEntry.getValue();
    final List<String> requestBody =
        parseMediaTypes(nextInfo.getConsumesCondition().getExpressions());
    final List<String> responseBody =
        parseMediaTypes(nextInfo.getProducesCondition().getExpressions());
    for (final String path : nextInfo.getPatternsCondition().getPatterns()) {
      final List<String> methods = Method.parseMethods(nextInfo.getMethodsCondition().getMethods());
      for (final String method : methods) {
        Endpoint endpoint =
            new Endpoint()
                .type(Endpoint.Type.REST)
                .operation(Endpoint.Operation.HTTP_REQUEST)
                .resource(method + " " + path)
                .path(path)
                .method(method)
                .requestBodyType(requestBody)
                .responseBodyType(responseBody);
        if (nextHandler != null) {
          final Map<String, String> metadata = new HashMap<>();
          metadata.put("handler", nextHandler.toString());
          endpoint.metadata(metadata);
        }
        if (first) {
          endpoint.first(true);
          first = false;
        }
        queue.add(endpoint);
      }
    }
  }

  private List<String> parseMediaTypes(final Set<MediaTypeExpression> expressions) {
    if (expressions == null || expressions.isEmpty()) {
      return null;
    }
    final List<String> result = new ArrayList<>(expressions.size());
    for (final MediaTypeExpression expression : expressions) {
      result.add(expression.toString());
    }
    return result;
  }
}
