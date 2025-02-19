package datadog.trace.instrumentation.springweb;

import static datadog.trace.api.appsec.api.security.model.Endpoint.Operation.HTTP_REQUEST;
import static datadog.trace.api.appsec.api.security.model.Endpoint.Type.REST;

import datadog.trace.api.appsec.api.security.model.Endpoint;
import datadog.trace.api.appsec.api.security.model.Endpoint.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.MediaTypeExpression;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

public class RequestMappingInfoInterator implements Iterator<Endpoint> {

  private final Iterator<Map.Entry<RequestMappingInfo, HandlerMethod>> delegate;
  private final Queue<Endpoint> queue = new LinkedList<>();

  public RequestMappingInfoInterator(final Map<RequestMappingInfo, HandlerMethod> mappings) {
    delegate = mappings.entrySet().iterator();
    fetchNext();
  }

  @Override
  public boolean hasNext() {
    return !queue.isEmpty();
  }

  @Override
  public Endpoint next() {
    Endpoint result = queue.poll();
    if (result == null) {
      throw new NoSuchElementException();
    }
    if (queue.isEmpty()) {
      fetchNext();
    }
    return result;
  }

  private void fetchNext() {
    if (!delegate.hasNext()) {
      return;
    }
    final Map.Entry<RequestMappingInfo, HandlerMethod> nextEntry = delegate.next();
    final RequestMappingInfo nextInfo = nextEntry.getKey();
    final HandlerMethod nextHandler = nextEntry.getValue();
    for (final String path : nextInfo.getPatternsCondition().getPatterns()) {
      final List<Method> methods = new LinkedList<>();
      if (nextInfo.getMethodsCondition().getMethods().isEmpty()) {
        methods.add(Method.ALL);
      } else {
        for (final RequestMethod method : nextInfo.getMethodsCondition().getMethods()) {
          methods.add(Method.parseMethod(method.name()));
        }
      }
      for (final Method method : methods) {
        final Endpoint endpoint = new Endpoint();
        endpoint.setType(REST);
        endpoint.setOperation(HTTP_REQUEST);
        endpoint.setPath(path);
        endpoint.setMethod(method);
        endpoint.setRequestBodyType(
            parseMediaTypes(nextInfo.getConsumesCondition().getExpressions()));
        endpoint.setResponseBodyType(
            parseMediaTypes(nextInfo.getProducesCondition().getExpressions()));
        final Map<String, Object> metadata = new HashMap<>();
        metadata.put("handler", nextHandler.toString());
        endpoint.setMetadata(metadata);
        queue.add(endpoint);
      }
    }
  }

  private List<String> parseMediaTypes(final Set<MediaTypeExpression> expressions) {
    if (expressions.isEmpty()) {
      return null;
    }
    final List<String> result = new ArrayList<>(expressions.size());
    for (final MediaTypeExpression expression : expressions) {
      result.add(expression.toString());
    }
    return result;
  }
}
